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
        while(b != '\n'){
            b = pipe.readByte();
            if(b != '\r' && b != '\n')
                bytes.write(b);
        }
        return decodeUTF8(bytes.toByteArray());
    }
    
    static RandomAccessFile openPipe(String pipeName) {
        // Must wait for STAT.EXE to create the named pipe before the open will succeed
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
            System.out.println("Command line: " + cmd);
            
            Process p = Runtime.getRuntime().exec(cmd, null, new File(System.getenv("STACLI_HOME")));
            
            RandomAccessFile pipe = null;
            
            // Must wait for STAT.EXE to create the named pipe before the open will succeed
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
            
            Scanner stdin = new Scanner(System.in);
            while(true){
                System.out.println("Enter STAT.EXE run verb arguments (without the 'run') to submit task(s) for execution. Enter blank line to exit.");
                String input = stdin.nextLine();
                if (input == null || input.isEmpty()) 
                    break;
                pipe.write(encodeUTF8(input + "\n"));

                System.out.println(readLine(pipe));
                System.out.println();
            }
//            String echoText = "Hello word\n";
//            // write to pipe
//            pipe.write ( echoText.getBytes() );
//            // read response
//            String echoResponse = pipe.readLine();
//            System.out.println("Response: " + echoResponse );
//            pipe.close();            


//            BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
//            String line;
//            while ((line = input.readLine()) != null) {
//                System.out.println(line);
//            }
//            input.close();
        }
        catch(Exception e){
            System.out.println("Error: " + e.getMessage());
        }
    }
    
}
