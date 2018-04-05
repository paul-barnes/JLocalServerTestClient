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

            srv = new StatServer(args);
            Scanner stdin = new Scanner(System.in);

            while (true) {
                
                if (!srv.isAlive()) {
                    System.out.println("The server is not responding. Attempting to start a new STAT process.");
                    srv.close();
                    srv = new StatServer(args);
                }

                System.out.println("Enter STAT.EXE run verb arguments (without the 'run') to submit task(s) for execution. Enter blank line to exit.");
                String input = stdin.nextLine();
                if (input == null || input.isEmpty())
                    break;

                if (!srv.isAlive()){
                    System.out.println("The server is not responding. Attempting to start a new STAT process.");
                    srv.close();
                    srv = new StatServer(args);
                    System.out.println(String.format("Successfully connected to a new STAT process. Sending the command [%s]", input));
                }
                
                try{
                    String reply = srv.sendMessageAndGetReply(input);
                    System.out.println(reply);
                    System.out.println();
                }
                catch(TimeoutException e){
                    System.out.println("The operation timed out. Attempting to close STAT process and create a new one.");
                    srv.close();
                    srv = new StatServer(args);
                    System.out.println("Successfully connected to a new STAT process");
                }
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
        finally{
            if(srv != null)
                srv.close();
        }
    }

}
