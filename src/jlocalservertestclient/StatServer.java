/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jlocalservertestclient;

import java.io.*;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pbarnes
 * Class StreamRedirector reads from an InputStream and copies all data read to 
 * an OutputStream in a separate thread. Used to capture the stdout and stderr
 * from the STAT.EXE process. Previously we simply let the stdout/stderr accumulate
 * and read it all after calling into stat.exe, but if one of the output buffers
 * gets too full without us emptying it, the stat process can hang while trying
 * to write log entries to stdout. See docs of java.lang.Process: "Because some 
 * native platforms only provide limited buffer size for standard input and output 
 * streams, failure to promptly write the input stream or read the output stream
 * of the subprocess may cause the subprocess to block, or even deadlock."
 * With this helper, we read all data as soon as it becomes available, and write 
 * to a ByteArrayOutputStream for logging/error reporting after the call to STAT.
 */
class StreamRedirector extends Thread {

   InputStream _is;
   OutputStream _os;
   byte[] _buff = new byte[256];

   StreamRedirector(InputStream is, OutputStream os) {
      _is = is;
      _os = os;
   }

   public void run() {
      try {
         int cb;
         while ((cb = _is.read(_buff)) != -1) 
            _os.write(_buff, 0, cb);
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
}

public class StatServer {

   private String _pipeName;
   private Process _statProcess;
   private RandomAccessFile _pipe;
   private ExecutorService _executorService;
   private ByteArrayOutputStream _stdOut;
   private ByteArrayOutputStream _stdErr;

   private final static int TIMEOUT_INTERVAL_SECONDS = 30;
   private final static Charset UTF8_CHARSET = Charset.forName("UTF-8");

   static String decodeUTF8(byte[] bytes) {
      return new String(bytes, UTF8_CHARSET);
   }

   static byte[] encodeUTF8(String string) {
      return string.getBytes(UTF8_CHARSET);
   }

   public StatServer() {
      _executorService = null;
      _pipeName = null;
      _statProcess = null;
      _pipe = null;
      _stdOut = null;
      _stdErr = null;
   }

   public void startServer(String enginePath, String[] args) throws IOException,
           InterruptedException, Exception {

      init(enginePath, String.join(" ", args));
   }

   public void startServer(String enginePath) throws IOException,
           InterruptedException, Exception {

      init(enginePath, null);
   }

   public void startServer(String enginePath, String args) throws IOException,
           InterruptedException, Exception {

      init(enginePath, args);
   }

   private void init(String enginePath, String cmd_line) throws IOException,
           InterruptedException, Exception {
      _executorService = Executors.newFixedThreadPool(1);
      _pipeName = "\\\\.\\Pipe\\jstat-"
              + java.util.UUID.randomUUID().toString();
      _statProcess = createSTATProcess(enginePath, _pipeName, cmd_line);

      _stdOut = new ByteArrayOutputStream();
      _stdErr = new ByteArrayOutputStream();
      
      StreamRedirector stdoutRedirector = new StreamRedirector(_statProcess.getInputStream(), _stdOut);            
      StreamRedirector stderrRedirector = new StreamRedirector(_statProcess.getErrorStream(), _stdErr);            
      stdoutRedirector.setName("stdOutRedirector");
      stderrRedirector.setName("stdErrRedirector");
            
      stdoutRedirector.start();
      stderrRedirector.start();      
      
      _pipe = connectPipe(_pipeName);
   }

   private void shutdownExecutor() {

      if (_executorService == null) {
         return;
      }

      try {
         // Disable new tasks from being submitted
         _executorService.shutdown();
         try {
            // Wait a while for existing tasks to terminate
            if (!_executorService.awaitTermination(2, TimeUnit.SECONDS)) {
               _executorService.shutdownNow(); // Cancel currently executing tasks
               // Wait a while for tasks to respond to being cancelled
               if (!_executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                  System.err.println("TRACE: Pool did not terminate");
               }
            }
         } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            _executorService.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
         }
      } catch (Exception e) {
         System.err.println("Exception occured in StatServer.shutdownExecutor:");
         e.printStackTrace();
      } finally {
         _executorService = null;
      }
   }

   private void closePipe() {
      try {
         if (_pipe != null) {
            // _pipe.close will end the STAT server process id it is listening
            // however it will block if the server is not responding/hung. 
            // execute in separate thread and kill the process if it does not respond
            Thread thread = new Thread(new Runnable() {
               @Override
               public void run() {
                  //System.out.println("TRACE: calling _pipe.close");
                  try {
                     _pipe.close();
                     //System.out.println("TRACE: _pipe.close returned");
                  } catch (Exception e) {
                     System.err.println("_pipe.close threw an exception");
                     e.printStackTrace();
                  }
               }
            });
            thread.setName("PipeShutdownThread");
            thread.start();

            // wait for millis milliseconds for the thread to finish
            int millis = 1000;
            thread.join(millis);
         }
      } catch (Exception e) {
         System.err.println("Exception occured in StatServer.closePipe:");
         e.printStackTrace();
      } finally {
         _pipe = null;
      }
   }

   private void shutdownServer() {
      try {
         if (_statProcess != null) {

            if (_pipe != null) 
               closePipe();

            _stdOut = null;
            _stdErr = null;

            // closing the pipe should have closed the server, 
            // if it was in a healthy state
            if (!_statProcess.waitFor(3, TimeUnit.SECONDS)) {
               // if the stat process did not shut down cleanly 
               // when we closed the pipe, kill it now
               System.err.println("TRACE: STAT process has not shut down. Killing it.");
               _statProcess.destroyForcibly();
            }
         }
      } catch (Exception e) {
         System.err.println("Exception occured in StatServer.shutdownServer:");
         e.printStackTrace();
      } finally {
         _statProcess = null;
      }
   }

   public void close() {
      shutdownExecutor();
      closePipe();
      shutdownServer();
      _pipeName = null;
   }

//   private static String readAllAvailable(InputStream inputStream) throws IOException {
//      if (inputStream.available() > 0) {
//         ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//         byte[] buffer = new byte[256];
//         int bytesRead = 0;
//         while (inputStream.available() > 0 && (bytesRead = inputStream.read(buffer)) >= 0) {
//            outputStream.write(buffer, 0, bytesRead);
//         }
//         return outputStream.toString();
//      }
//      return new String();
//   }

   public String getStdOut() throws IOException {
      // stat.exe stdout has been accumulated in _stdOut with help of 
      // StreamRedirector class; here we return everything we've captured
      // since last call, and empty the buffer; this is a destructive read.
      String s = _stdOut.toString();
      _stdOut.reset();
      return s;
   }

   public String getStdErr() throws IOException {
      // stat.exe stderr has been accumulated in _stdErr with help of 
      // StreamRedirector class; here we return everything we've captured
      // since last call, and empty the buffer; this is a destructive read.
      String s = _stdErr.toString();
      _stdErr.reset();
      return s;
   }

   public void sendMessage(String msg) throws IOException,
           InterruptedException, ExecutionException, TimeoutException {
      sendMessage(msg, TIMEOUT_INTERVAL_SECONDS);
   }

   public void sendMessage(String msg, int timeoutIntervalSeconds)
           throws IOException, InterruptedException,
           ExecutionException, TimeoutException {

      Future future = _executorService.submit(new Callable<Void>() {
         public Void call() throws Exception {
            _pipe.write(encodeUTF8(msg + "\n"));
            return null;
         }
      });

      try {
         future.get(timeoutIntervalSeconds, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
         future.cancel(true);
         System.err.println("Timeout in StatServer.sendMessage");
         throw e;
      }
   }

   public int getReply() throws IOException, InterruptedException,
           ExecutionException, TimeoutException {
      return getReply(TIMEOUT_INTERVAL_SECONDS);
   }

   public int getReply(int timeoutIntervalSeconds) throws IOException,
           InterruptedException, ExecutionException, TimeoutException {

      Future future = _executorService.submit(new Callable<Integer>() {
         public Integer call() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte b = 0;
            try {
               while (b != '\n') {
                  b = _pipe.readByte();
                  if (b != '\r' && b != '\n') {
                     bytes.write(b);
                  }
               }
            } catch (EOFException e) {
               throw new EOFException(
                       "An error occurred while reading from the pipe. "
                       + "The STAT.EXE process may have ended.");
            }
            return Integer.parseInt(decodeUTF8(bytes.toByteArray()));
         }
      });

      try {
         return (int) future.get(timeoutIntervalSeconds, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
         System.err.println("Timeout in StatServer.getReply");
         throw e;
      }
   }

   public int sendMessageAndGetReply(String msg, int timeoutIntervalSeconds)
           throws IOException, InterruptedException,
           ExecutionException, TimeoutException {

      sendMessage(msg, timeoutIntervalSeconds);
      return getReply(timeoutIntervalSeconds);
   }

   public int sendMessageAndGetReply(String msg)
           throws IOException, InterruptedException,
           ExecutionException, TimeoutException {

      return sendMessageAndGetReply(msg, TIMEOUT_INTERVAL_SECONDS);
   }

   public boolean isAlive() {
      try {
         if (_statProcess != null && _statProcess.isAlive()) {
            int timeoutSeconds = 3;
            if (sendMessageAndGetReply("alive", timeoutSeconds) == 0) {
               return true;
            }
         }
      } catch (Exception e) {
         System.err.println("Exception in isAlive. Returning false.");
         e.printStackTrace();
      }
      return false;
   }

   private static Process createSTATProcess(String enginePath, String pipeName, String cmd_line)
           throws IOException {
      String cmd = enginePath + "\\STAT.EXE listen " + pipeName;
      if (cmd_line != null && cmd_line.length() > 0) {
         cmd += " " + cmd_line;
      }
      Process p = Runtime.getRuntime().exec(cmd, null, new File(enginePath));
      return p;
   }

   private static RandomAccessFile connectPipe(String pipeName)
           throws InterruptedException, Exception {
      // Must wait for STAT.EXE to create the named pipe before the open will succeed;
      // Here we will retry periodically for a while and then fail if it cannot be opened
      RandomAccessFile pipe = null;
      Instant then = Instant.now().plusSeconds(10);
      while (Instant.now().isBefore(then)) {
         Thread.sleep(200);
         try {
            pipe = new RandomAccessFile(pipeName, "rw");
            break;
         } catch (FileNotFoundException e) {
         }
      }
      if (pipe == null) {
         throw new Exception("StatServer failed to connect to the pipe");
      }
      return pipe;
   }
}
