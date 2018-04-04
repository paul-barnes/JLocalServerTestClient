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
import java.util.concurrent.TimeUnit;

/**
 *
 * @author pbarnes
 */
public class StatServer {

    private String _pipeName;
    private Process _statProcess;
    private RandomAccessFile _pipe;

    private final static Charset UTF8_CHARSET = Charset.forName("UTF-8");

    static String decodeUTF8(byte[] bytes) {
        return new String(bytes, UTF8_CHARSET);
    }

    static byte[] encodeUTF8(String string) {
        return string.getBytes(UTF8_CHARSET);
    }

    public StatServer(String[] args) throws IOException, InterruptedException, Exception {
        _pipeName = java.util.UUID.randomUUID().toString();
        _statProcess = createSTATProcess(_pipeName, args);
        _pipe = connectPipe(_pipeName);
    }

    public void close(){
        try{
            _pipe.close();
            if(!_statProcess.waitFor(5, TimeUnit.SECONDS))
                _statProcess.destroyForcibly();
        }
        catch(Exception e) {
        }
        _pipeName = null;
        _statProcess = null;
        _pipe = null;
    }
    public void sendMessage(String msg) throws IOException {
        _pipe.write(encodeUTF8(msg + "\n"));
    }

    public String getReply() throws IOException {
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

    public String sendMessageAndGetReply(String msg) throws IOException {
        sendMessage(msg);
        return getReply();
    }

    public boolean isAlive() {
        try {
            if (_statProcess.isAlive()) {
                if (sendMessageAndGetReply("alive").equals("OK"))
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
