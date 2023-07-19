package minum.database;

import minum.Context;
import minum.logging.ILogger;
import minum.utils.ActionQueue;
import minum.utils.FileUtils;
import minum.utils.StacktraceUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static minum.utils.FileUtils.writeString;
import static minum.utils.Invariants.mustBeTrue;

/**
 * This allows us to run some disk-persistence operations more consistently
 * on any data that extends from {@link ISimpleDataType}
 * @param <T> the type of data we'll be persisting
 */
public class AlternateDatabaseDiskPersistenceSimpler<T extends AlternateSimpleDataTypeImpl<?>> {

    /**
     * The suffix we will apply to each database file
     */
    static final String databaseFileSuffix = ".ddps";
    private final T emptyInstance;

    /**
     * The full path to the file that contains the most-recent index
     * for this data.  As we add new files, each gets its own index
     * value.  When we start the program, we use this to determine
     * where to start counting for new indexes.
     */
    private final String fullPathForIndexFile;

    final AtomicLong index;

    private final Path dbDirectory;
    private final ActionQueue actionQueue;
    private final ILogger logger;
    private final List<T> data;
    private boolean hasLoadedData;

    /**
     * Constructs a disk-persistence class well-suited for your data.
     * @param dbDirectory the directory for a particular domain (*not* the top-level
     *                     directory).  For example, if the top-level directory is
     *                     "db", and we're building this for a domain "foo", we
     *                     might expect to receive "db/foo" here.
     */
    public AlternateDatabaseDiskPersistenceSimpler(Path dbDirectory, Context context, T instance) {
        this.hasLoadedData = false;
        this.data = new ArrayList<>();
        actionQueue = new ActionQueue("DatabaseWriter " + dbDirectory, context).initialize();
        this.logger = context.getLogger();
        this.dbDirectory = dbDirectory;
        this.fullPathForIndexFile = dbDirectory + "/index" + databaseFileSuffix;
        this.emptyInstance = instance;

        if (Files.exists(Path.of(fullPathForIndexFile))) {
            this.index = new AtomicLong(Long.parseLong(FileUtils.readFile(fullPathForIndexFile)));
        } else {
            this.index = new AtomicLong(1);
        }

        actionQueue.enqueue("create directory" + dbDirectory, () -> {
            try {
                FileUtils.makeDirectory(logger, dbDirectory);
            } catch (IOException ex) {
                logger.logAsyncError(() -> StacktraceUtils.stackTraceToString(ex));
            }
        });

    }

    /**
     * This function will stop the minum.database persistence cleanly.
     * <p>
     * In order to do this, we need to wait for our threads
     * to finish their work.  In particular, we
     * have offloaded our file writes to [actionQueue], which
     * has an internal thread for serializing all actions
     * on our minum.database
     * </p>
     */
    public void stop() {
        actionQueue.stop();
    }

    /**
     * Similar to {@link #stop()} but gives more control over how long
     * we'll wait before crashing it closed.  See {@link ActionQueue#stop(int, int)}
     */
    public void stop(int count, int sleepTime) {
        actionQueue.stop(count, sleepTime);
    }

    /**
     * takes any serializable data and writes it to disk
     *
     * @param newData the data we are writing
     */
    public void persistToDisk(T newData) {
        // load data if needed
        if (!hasLoadedData) loadData();

        // deal with the in-memory portion
        newData.setIndex(index.getAndIncrement());
        data.add(newData);

        // now handle the disk portion
        final String fullPath = makeFullPathFromData(newData);
        actionQueue.enqueue("persist data to disk", () -> {
            writeString(fullPath, newData.serialize());
            writeString(fullPathForIndexFile, String.valueOf(newData.getIndex()));
        });
    }

    /**
     * Deletes a piece of data from the disk
     *
     * @param dataToDelete the data we are serializing and writing
     * @return true if this list contained the specified element (or
     * equivalently, if this list changed as a result of the call).
     */
    public void deleteOnDisk(T dataToDelete) {
        // load data if needed
        if (!hasLoadedData) loadData();

        // deal with the in-memory portion
        boolean result = data.remove(dataToDelete.getIndex());

        if (data.isEmpty()) {
            index.set(1);
        }
        // now handle the disk portion
        final String fullPath = makeFullPathFromData(dataToDelete);
        actionQueue.enqueue("delete data from disk", () -> {
            try {
                Files.delete(Path.of(fullPath));
            } catch (Exception ex) {
                logger.logAsyncError(() -> "failed to delete file "+fullPath+" during deleteOnDisk");
            }
        });
    }


    /**
     * updates an element by replacing the element having the same id
     * if the data to update is not found, throw an exception
     */
    public void updateOnDisk(T dataUpdate) {
        // load data if needed
        if (!hasLoadedData) loadData();

        // deal with the in-memory portion
        int indexFound = -1;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).getIndex() == dataUpdate.getIndex()) {
                indexFound = i;
                break;
            }
        }

        if (indexFound == -1) {
            throw new RuntimeException("no data was found with id of " + dataUpdate.getIndex());
        } else {
            data.set(indexFound, dataUpdate);
        }

        // now handle the disk portion
        final String fullPath = makeFullPathFromData(dataUpdate);
        final var file = new File(fullPath);

        actionQueue.enqueue("update data on disk", () -> {
            // if the file isn't already there, throw an exception
            mustBeTrue(file.exists(), "we were asked to update "+file+" but it doesn't exist");
            writeString(fullPath, dataUpdate.serialize());
        });
    }

    /**
     * The full path to the files of this data
     */
    private String makeFullPathFromData(T data) {
        return dbDirectory + "/" + data.getIndex() + databaseFileSuffix;
    }

    /**
     * Grabs all the data from disk and returns it as a list.  This
     * method is run by various programs when the system first loads.
     */
    List<T> loadDataFromDisk() {
        if (! Files.exists(dbDirectory)) {
            logger.logDebug(() -> dbDirectory + " directory missing, creating empty list of data");
            return new ArrayList<>();
        }

        try (final var pathStream = Files.walk(dbDirectory)) {
            final var listOfFiles = pathStream.filter(path -> Files.exists(path) && Files.isRegularFile(path)).toList();
            for (Path p : listOfFiles) {
                readAndDeserialize(p);
            }
        } catch (IOException e) { // if we fail to walk() the dbDirectory.  I don't even know how to test this.
            throw new RuntimeException(e);
        }
        return data;
    }

    void readAndDeserialize(Path p) throws IOException {
        String fileContents;
        fileContents = Files.readString(p);
        if (fileContents.isBlank()) {
            logger.logDebug( () -> p.getFileName() + " file exists but empty, skipping");
        } else {
            try {
                deserialize(fileContents);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize "+ p +" with data (\""+fileContents+"\")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private T deserialize(String fileContents) {
        return (T) emptyInstance.deserialize(fileContents);
    }

    public Stream<T> stream() {
        // load data if needed
        if (!hasLoadedData) loadData();

        return data.stream();
    }

    private void loadData() {
        data.addAll(loadDataFromDisk());
        hasLoadedData = true;
    }

}
