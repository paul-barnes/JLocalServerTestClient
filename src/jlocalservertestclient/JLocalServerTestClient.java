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
import java.util.concurrent.TimeoutException;
import com.diogonunes.jcdp.color.ColoredPrinter;
import com.diogonunes.jcdp.color.api.Ansi.*;

/**
 * ConsoleColorSetter intended for use with try-with-resources statement 
 * to implement raii pattern and auto reset the console back to defaults 
 * after autoclose
 * 
 * @author pbarnes
 */
class ConsoleColorSetter implements AutoCloseable, Closeable {
    private ColoredPrinter _printer;
    
    public ConsoleColorSetter(FColor color){
        init(Attribute.NONE, color, BColor.NONE);
    }
    public ConsoleColorSetter(Attribute attr, FColor fcolor){
        init(attr, fcolor, BColor.NONE);
    }
    public ConsoleColorSetter(Attribute attr, FColor fcolor, BColor bcolor){
        init(attr, fcolor, bcolor);
    }
    private void init(Attribute attr, FColor fcolor, BColor bcolor){
        _printer = new ColoredPrinter.Builder(1, false)
            .attribute(attr).foreground(fcolor).background(bcolor).build();
        _printer.print(""); // empty print to get the ColoredPrinter to set the console color properties 
    }
            
    public void close(){
        _printer.clear();
    }
}

/**
 *
 * @author pbarnes
 */
public class JLocalServerTestClient {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        
        StatServer srv=null;
        try {
            if (args.length > 0 && (args[0] == "-h" || args[0] == "--help")) {
                System.out.println("Launches STAT.EXE in localserver mode, then submits 'run' verb commands to it.");
                System.out.println("Any arguments provided will be passed on to STAT.EXE (e.g., username, password, loglevel, log-file");
                System.out.println("If no --log-file argument provided, configures STAT.EXE to log to file 'stat.log' in current directory");
                return;
            }

            srv = new StatServer();
            srv.startServer(System.getenv("STACLI_HOME"), args);

            String stdOut = srv.getStdOut();
            String stdErr = srv.getStdErr();
            
            // write the logging info from std out
            try (ConsoleColorSetter c = new ConsoleColorSetter(Attribute.LIGHT, FColor.BLACK)) {
                System.out.println(stdOut);
            }            
            
            Scanner stdin = new Scanner(System.in);

            while (true) {
                
                System.out.println("Enter STAT.EXE run command to submit task(s) for execution. Enter blank line to exit.");
                String input = stdin.nextLine();
                if (input == null || input.isEmpty())
                    break;

                if(!checkServer(srv, args))
                    System.err.println(String.format("STAT process was restarted. Sending the command [%s]", input));
                
                try{
                    int ret = srv.sendMessageAndGetReply(input);
                    stdOut = srv.getStdOut();
                    stdErr = srv.getStdErr();
                    System.out.println();
                    try (ConsoleColorSetter c = new ConsoleColorSetter(Attribute.LIGHT, FColor.BLACK)) {
                        System.out.println(stdOut);
                    }
                    
                    if (ret == 0)
                    {
                        try (ConsoleColorSetter c = new ConsoleColorSetter(Attribute.LIGHT, FColor.GREEN)) {
                            System.out.println("SUCCESS!");
                        }
                    }
                    else
                    {
                        try (ConsoleColorSetter c = new ConsoleColorSetter(Attribute.LIGHT, FColor.RED)) {
                            System.out.println("ERROR 0x" + Integer.toHexString(ret) + ":");
                            System.out.println(stdErr);
                        }
                    }
                    System.out.println();
                }
                catch(TimeoutException e){
                    System.err.println("The operation timed out. Attempting to close STAT process and create a new one.");
                    srv.close();
                    srv.startServer(System.getenv("STACLI_HOME"), args);
                    System.err.println("Successfully connected to a new STAT process");
                }
            }
        } catch (Exception e) {
            System.err.println("A fatal error occured:");
            e.printStackTrace();
        }
        finally{
            if(srv != null)
                srv.close();
        }
    }

    private static boolean checkServer(StatServer srv, String[] args) throws IOException, InterruptedException, Exception {
        if (!srv.isAlive()) {
            System.out.println("The server is not responding. Attempting to start a new STAT process.");
            srv.close();
            srv.startServer(System.getenv("STACLI_HOME"), args);

            String stdOut = srv.getStdOut();
            String stdErr = srv.getStdErr();

            // write the logging info from std out
            try (ConsoleColorSetter c = new ConsoleColorSetter(Attribute.LIGHT, FColor.BLACK)) {
                System.out.println(stdOut);
            }
            System.out.println("Successfully connected to a new STAT process");
            return false;
        }
        return true;
    }
}
