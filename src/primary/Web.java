package primary;

import java.io.IOException;
import java.net.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class Web {

  private ILogger logger;
  public final String HTTP_CRLF = "\r\n";

  private void addToSetOfServers(SimpleConcurrentSet<SocketWrapper> setOfServers, SocketWrapper sw) {
    setOfServers.add(sw);
    int size = setOfServers.size();
    logger.logDebug(() -> "added " + sw + " to setOfServers. size: " + size);
  }

  private void removeFromSetOfServers(SimpleConcurrentSet<SocketWrapper> setOfServers, SocketWrapper sw) {
    setOfServers.remove(sw);
    int size = setOfServers.size();
    logger.logDebug(() -> "removed " + sw + " from setOfServers. size: " + size);
  }

  public Web(ILogger logger) {
    if (logger == null) {
      this.logger = msg -> System.out.println(msg.get());
      this.logger.logDebug(() -> "Using a local logger");
    } else {
      this.logger = logger;
      this.logger.logDebug(() -> "Using a supplied logger");
    }
  }

  /**
   * This wraps Sockets to make them simpler / more particular to our use case
   */
  class SocketWrapper implements AutoCloseable {

    private final Socket socket;
    private final OutputStream writer;
    private final BufferedReader reader;
    private SimpleConcurrentSet<SocketWrapper> setOfServers;

    public SocketWrapper(Socket socket) {
      this.socket = socket;
      try {
        writer = socket.getOutputStream();
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public SocketWrapper(Socket socket, SimpleConcurrentSet<SocketWrapper> scs) {
      this(socket);
      this.setOfServers = scs;
    }

    public void send(String msg) {
      try {
        writer.write(msg.getBytes());
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public String readLine() {
      try {
        return reader.readLine();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public String getLocalAddr() {
      return socket.getLocalAddress().getHostAddress();
    }

    public int getLocalPort() {
      return socket.getLocalPort();
    }

    public SocketAddress getRemoteAddr() {
      return socket.getRemoteSocketAddress();
    }

    @Override
    public void close() {
      try {
        socket.close();
        if (setOfServers != null) {

          removeFromSetOfServers(setOfServers, this);
        }
      } catch(Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  /**
   * The purpose here is to make it marginally easier to
   * work with a ServerSocket.
   *
   * First, instantiate this class using a running serverSocket
   * Then, by running the start method, we gain access to
   * the server's socket.  This way we can easily test / control
   * the server side but also tie it in with an ExecutorService
   * for controlling lots of server threads.
   */
  class Server implements AutoCloseable{
    private final ServerSocket serverSocket;
    private SimpleConcurrentSet<SocketWrapper> setOfServers;

    /**
     * This is the future returned when we submitted the
     * thread for the central server loop to the ExecutorService
     */
    public Future<?> centralLoopFuture;

    public Server(ServerSocket ss) {
      this.serverSocket = ss;
      setOfServers = new SimpleConcurrentSet<>();
    }

    public void start(ExecutorService es, Consumer<SocketWrapper> handler) {
      Thread t = new Thread(() -> {
        try {
          while (true) {
            logger.logDebug(() -> "server waiting to accept connection");
            Socket freshSocket = serverSocket.accept();
            SocketWrapper sw = new SocketWrapper(freshSocket, setOfServers);
            logger.logDebug(() -> String.format("server accepted connection: remote: %s", sw.getRemoteAddr()));
            addToSetOfServers(setOfServers, sw);
            if (handler != null) {
              es.submit(new Thread(() -> handler.accept(sw)));
            }
          }
        } catch (SocketException ex) {
          if (ex.getMessage().contains("Socket closed")) {
            // just swallow the complaint.  accept always
            // throw this exception when we run close()
            // on the server socket
          } else {
            throw new RuntimeException(ex);
          }
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      });
      this.centralLoopFuture = es.submit(t);
    }

    public void close() {
      try {
        serverSocket.close();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public String getHost() {
      return serverSocket.getInetAddress().getHostAddress();
    }

    public int getPort() {
      return serverSocket.getLocalPort();
    }

    /**
     * This is a helper method to find the server SocketWrapper
     * connected to a client SocketWrapper.
     */
    public SocketWrapper getServer(SocketWrapper sw) {
      return getSocketWrapperByRemoteAddr(sw.getLocalAddr(), sw.getLocalPort());
    }


    /**
     * This is a program used during testing so we can find the server
     * socket that corresponds to a particular client socket.
     *
     * Due to the circumstances of the TCP handshake, there's a bit of
     * time where the server might still be "figuring things out", and
     * when we come through here the server hasn't yet finally come
     * out of "accept" and been put into the list of current server sockets.
     *
     * For that reason, if we come in here and don't find it initially, we'll
     * sleep and then try again, up to three times.
     */
    private SocketWrapper getSocketWrapperByRemoteAddr(String address, int port) {
      int maxLoops = 3;
      for (int loopCount = 0; loopCount < maxLoops; loopCount++ ) {
        List<SocketWrapper> servers = setOfServers
                .asStream()
                .filter((x) -> x.getRemoteAddr().equals(new InetSocketAddress(address, port)))
                .toList();
        if (servers.size() > 1) {
          throw new RuntimeException("Too many sockets found with that address");
        } else if (servers.size() == 1) {
          return servers.get(0);
        }
        int finalLoopCount = loopCount;
        logger.logDebug(() -> String.format("no server found, sleeping on it... (attempt %d)", finalLoopCount + 1));
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      throw new RuntimeException("No socket found with that address");
    }

  }


  /**
   * Encapsulates the idea of a program that only has
   * mechanisms for speaking and listening.  Its interface is
   * purely about that, with an emphasis on the kinds of
   * speaking and listening that a web server considers important.
   *
   * For example, in HTTP, each new line must end with CR + LF,
   * per the various appropriate RFC's.
   * See https://datatracker.ietf.org/doc/html/rfc2616
   *
   * With this class, we can avoid some of those considerations. maybe.
   *
   * On the other hand maybe I'm abstracting too soon.  Well,
   * it *is* 11:43 at night, I probably am.
   */
  class Talker {

    private final SocketWrapper sw;

    public Talker(SocketWrapper sw) {
      this.sw = sw;
    }

    public void sendLine(String s) {
      sw.send(s + HTTP_CRLF);
    }

    public String readLine() {
      return sw.readLine();
    }
  }

  public Web.Server startServer(ExecutorService es, Consumer<SocketWrapper> handler) {
    try {
      int port = 8080;
      ServerSocket ss = new ServerSocket(port);
      logger.logDebug(() -> String.format("Just created a new ServerSocket: %s", ss));
      Server server = new Server(ss);
      server.start(es, handler);
      return server;
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Create a listening server
   */
  public Web.Server startServer(ExecutorService es) {
    return startServer(es, null);
  }

  public Web.SocketWrapper startClient(Server server) {
    try {
      Socket socket = new Socket(server.getHost(), server.getPort());
      logger.logDebug(() -> String.format("Just created new client socket: %s", socket));
      return new SocketWrapper(socket);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  public Web.Talker makeTalker(SocketWrapper sw) {
    return new Talker(sw);
  }

}
