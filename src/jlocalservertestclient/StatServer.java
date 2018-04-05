/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jlocalservertestclient;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pbarnes
 */
public class StatServer {

    private String _pipeName;
    private Process _statProcess;
    private RandomAccessFile _pipe;
    private ExecutorService _executorService;
    
    private final static int TIMEOUT_INTERVAL_SECONDS = 5;
    private final static Charset UTF8_CHARSET = Charset.forName("UTF-8");

    static String decodeUTF8(byte[] bytes) {
        return new String(bytes, UTF8_CHARSET);
    }

    static byte[] encodeUTF8(String string) {
        return string.getBytes(UTF8_CHARSET);
    }

    public StatServer(String[] args) throws IOException, InterruptedException, Exception {
        _executorService = Executors.newFixedThreadPool(1);
        _pipeName = java.util.UUID.randomUUID().toString();
        _statProcess = createSTATProcess(_pipeName, args);
        _pipe = connectPipe(_pipeName);
    }

    void shutdownExecutor(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    public void close(){
        try{
            if(_executorService != null)
               shutdownExecutor(_executorService);
            if(_pipe != null)
                _pipe.close();
            if(_statProcess != null)
                if(!_statProcess.waitFor(5, TimeUnit.SECONDS))
                    _statProcess.destroyForcibly();
        }
        catch(Exception e) {
        }
        _executorService = null;
        _pipeName = null;
        _statProcess = null;
        _pipe = null;
    }
    
    public void sendMessage(String msg) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        sendMessage(msg, TIMEOUT_INTERVAL_SECONDS);
    }
    
    public void sendMessage(String msg, int timeoutIntervalSeconds) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Future future = _executorService.submit(new Callable<Void>() {
            public Void call() throws Exception {
                _pipe.write(encodeUTF8(msg + "\n"));
                return null;
            }
        });
        
        try {
            future.get(timeoutIntervalSeconds, TimeUnit.SECONDS);
        } 
        catch(TimeoutException e){
            boolean b = future.cancel(true);
            System.out.println(String.format("TIMEOUT in sendMessage; cancel returned %s", b ? "true" : "false"));
            throw e;
        }
    }

    public String getReply() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return getReply(TIMEOUT_INTERVAL_SECONDS);
    }

    public String getReply(int timeoutIntervalSeconds) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Future future = _executorService.submit(new Callable<String>() {
            public String call() throws Exception {
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
                    throw new EOFException("An error occurred while reading from the pipe. The STAT.EXE process may have ended.");
                }
                return decodeUTF8(bytes.toByteArray());
            }
        });
        
        try {
            return (String)future.get(timeoutIntervalSeconds, TimeUnit.SECONDS);
        } 
        catch(TimeoutException e){
            boolean b = future.cancel(true);
            System.out.println(String.format("TIMEOUT in getReply; cancel returned %s", b ? "true" : "false"));
            throw e;
        }
    }

    public String sendMessageAndGetReply(String msg, int timeoutIntervalSeconds) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        sendMessage(msg, timeoutIntervalSeconds);
        return getReply(timeoutIntervalSeconds);
    }
    public String sendMessageAndGetReply(String msg) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        return sendMessageAndGetReply(msg, TIMEOUT_INTERVAL_SECONDS);
    }

    public boolean isAlive() {
        try {
            if (_statProcess != null && _statProcess.isAlive()) {
                int timeoutSeconds = 3;
                if (sendMessageAndGetReply("alive", timeoutSeconds).equals("OK"))
                    return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    private static Process createSTATProcess(String pipeName, String[] args) throws IOException {
        String cmd = System.getenv("STACLI_HOME") + "\\STAT.EXE localserver --pipe " + pipeName;
        if (args.length > 0) {
            cmd += " " + String.join(" ", args);
        }
        if (!cmd.contains("--log-file")) {
            cmd += " --log-file stat.log";
            System.out.println("STAT.EXE writing to log file 'stat.log'");
        }

        Process p = Runtime.getRuntime().exec(cmd, null, new File(System.getenv("STACLI_HOME")));
        return p;
    }

    private static RandomAccessFile connectPipe(String pipeName) throws InterruptedException, Exception {
        // Must wait for STAT.EXE to create the named pipe before the open will succeed;
        // Here we will retry periodically for a while and then fail if it cannot be opened
        RandomAccessFile pipe = null;
        Instant then = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(then)) {
            Thread.sleep(200);
            try {
                pipe = new RandomAccessFile("\\\\.\\pipe\\" + pipeName, "rw");
                break;
            } catch (FileNotFoundException e) {
            }
        }
        if (pipe == null) {
            throw new Exception("Failed to connect to the pipe");
        }
        return pipe;
    }

}
