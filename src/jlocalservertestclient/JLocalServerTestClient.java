/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jlocalservertestclient;

import java.io.*;
import java.time.*;
import java.util.*;
import java.nio.charset.Charset;
/**
 *
 * @author pbarnes
 */
public class JLocalServerTestClient {

    private final static Charset UTF8_CHARSET = Charset.forName("UTF-8");
    
    static String decodeUTF8(byte[] bytes) {
        return new String(bytes, UTF8_CHARSET);
    }

    static byte[] encodeUTF8(String string) {
        return string.getBytes(UTF8_CHARSET);
    }

    static String readLine(RandomAccessFile pipe) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte b=0;
        try {
            while(b != '\n'){
                b = pipe.readByte();
                if(b != '\r' && b != '\n')
                    bytes.write(b);
            }
        }
        catch(EOFException e){
            throw new EOFException("An error occurred while reading from the pipe. The STAT.EXE process may have ended.");
        }
        return decodeUTF8(bytes.toByteArray());
        
    }
    
    static void writeLine(RandomAccessFile pipe, String line) throws IOException {
        pipe.write(encodeUTF8(line + "\n"));
    }
    
    static RandomAccessFile openPipe(String pipeName) throws InterruptedException, Exception{
        // Must wait for STAT.EXE to create the named pipe before the open will succeed;
        // Here we will retry periodically for a while and then fail if it cannot be opened
        RandomAccessFile pipe = null;
        Instant then = Instant.now().plusSeconds(10);
        while(Instant.now().isBefore(then)){
            Thread.sleep(200);
            try{
                pipe = new RandomAccessFile("\\\\.\\pipe\\" + pipeName, "rw");
                break;
            }
            catch(FileNotFoundException e){
            }
        }
        if(pipe == null){
            throw new Exception("Failed to connect to the pipe");
        }
        return pipe;
    }
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            if(args.length > 0 && (args[0] == "-h" || args[0] == "--help"))
            {
                System.out.println("Launches STAT.EXE in localserver mode, then submits 'run' verb commands to it.");
                System.out.println("Any arguments provided will be passed on to STAT.EXE (e.g., username, password, loglevel, log-file");
                System.out.println("If no --log-file argument provided, configures STAT.EXE to log to file 'stat.log' in current directory");
                return;
            }
            
            String pipeName = java.util.UUID.randomUUID().toString();
            String cmd = System.getenv("STACLI_HOME") + "\\STAT.EXE localserver --pipe " + pipeName;
            if(args.length > 0)
                cmd += " " + String.join(" ", args);
            if (!cmd.contains("--log-file"))
            {
                cmd += " --log-file stat.log";
                System.out.println("STAT.EXE writing to log file 'stat.log'");
            }
            
            Process p = Runtime.getRuntime().exec(cmd, null, new File(System.getenv("STACLI_HOME")));
            
            RandomAccessFile pipe = openPipe(pipeName);
//            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pipe.getFD()), UTF8_CHARSET));
//            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pipe.getFD()), UTF8_CHARSET));
                        
            Scanner stdin = new Scanner(System.in);
            while(true){
                System.out.println("Enter STAT.EXE run verb arguments (without the 'run') to submit task(s) for execution. Enter blank line to exit.");
                String input = stdin.nextLine();
                if (input == null || input.isEmpty()) 
                    break;

//                writer.write(input);
//                writer.newLine();
//                writer.flush();
//                System.out.println(reader.readLine());

                writeLine(pipe, input);
                System.out.println(readLine(pipe));
                System.out.println();
            }
        }
        catch(Exception e){
            System.out.println("Error: " + e.getMessage());
        }
    }
    
}
