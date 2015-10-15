
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.zip.Deflater;

/* Simple file transfer across network to send files or directories (recursively) to another host.
 * To keep number of files and footprint small, almost all classes are combined in this one file.
 *
 * (c) amhlaobh@users.noreply.github.com
 * 
 * NB: Windows doesn't allow :, ?, *, <, >, ", |, / and \ in file names, so a file "a:b" will be created as "a" 
 */

public class Xfer2 {
    
    private static final int PORT = 9337;
    private static final int BLOCKSIZE = 1024 * 16;
    private static final String VERSION = "xfer3.4";
    private static Level logLevel = Level.INFO;
    private static final ThreadLocal<DateFormat> dateFormat =
        new ThreadLocal<DateFormat>() {
            @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss z");
                }
        };
    private static int blocksize = BLOCKSIZE;
    private static int deflaterLevel = Deflater.DEFAULT_COMPRESSION;
    private static boolean overwrite = false;
    private static boolean createCopy = false;
    private static boolean compress = false;
    private static String[] ipAddresses = null;
    private static boolean printProgressBar = false;
    private static int progressTicks = 40;
    private static final String EXISTS_WONT_OVERWRITE = "existsWontOverwrite";
    private static final String EXISTS_WILL_OVERWRITE = "existsWillOverwrite";
    private static final String EXISTS_NOT = "existsNot";
    private static final String FORCE_OVERWRITE = "forceOverwrite";
    private static final String DUPLICATE_SUFFIX = ".xfer";
    private static final String TICK_SYMBOL = "=";
    private static final byte[] NULL_ARR = new byte[]{0};
    private static long modifyWindow = 1000L;
    
    public Xfer2(){
        // just print out some diagnostics about myself
        String name = getClass().getName().replace('.', '/');
        String path = getClass().getResource("/" + name + ".class").toString();
        int idxSlash = path.indexOf('/');
        int idxExcl = path.lastIndexOf('!');
        if (idxSlash >= 0 && idxExcl >= 0){
            String myJarName = path.substring(idxSlash, idxExcl);
            log(Level.CONFIG, "My jar file name: "+myJarName);
        }
    }
    
    private static void writeToStream(OutputStream sendOs, String s)
            throws IOException {
        writeToStream(sendOs, s, true);
    }
    
    private static void writeToStream(OutputStream sendOs, String s, boolean flush) 
        throws IOException{
        sendOs.write(s.getBytes());
        // can't simply send write(0) because write(int) and write(byte[])
        // in CompressedBlockOutputStream apparently can't be mixed
        sendOs.write(NULL_ARR);
        if (flush) sendOs.flush();
        
    }

    private static String formatTransferRate(double transferTimeMillis, long transferBytes){
        double bytesPerSecond = (transferBytes*1000.0 / (transferTimeMillis));
        return String.format ("%s/s", formatKiBMiBGiB(bytesPerSecond));
    }

    /** Pretty print number of bytes in KiB, MiB and GiB. */
    private static String formatKiBMiBGiB(double bytes){
        String unit = "";
        double formattedBytes = 0.0;
        
        if (bytes < 1024 ) {
            unit = "bytes";
            formattedBytes = bytes;
        } else if (bytes < 1024.0*1024.0) {
            unit = "KiB";
            formattedBytes = bytes / 1024.0;
        } else if (bytes < 1024.0*1024.0*1024.0) {
            unit = "MiB";
            formattedBytes = bytes / (1024.0 * 1024.0);
        } else if (bytes < 1024.0*1024.0*1024.0*1024.0) {
            unit = "GiB";
            formattedBytes = bytes / (1024.0 * 1024.0 * 1024.0);
        }

        return String.format ("%4.2f %s", formattedBytes, unit);
    }

    /** Turns cygwin path into Windows path. */
    private static String cyg2win(String cyg){
        String win = cyg.replaceFirst("^/cygdrive/", "");
        if (cyg.equals(win)){
            log(Level.WARNING, String.format("%s is not a cygwin path", cyg));
            return cyg;
        }
        log(Level.FINEST, "win="+win);
        win = win.replaceFirst("/", Matcher.quoteReplacement(":\\"));
        log(Level.FINEST, "win="+win);
        win = win.replaceAll("/", Matcher.quoteReplacement("\\"));
        log(Level.FINEST, "win="+win);
        return win;
    }
    
    /** Receiver thread. */
    static class Receiver extends Thread {
        private ServerSocket serverSocket = null;
        private Socket recvSocket = null;
        private File targetDir = null;
        private int port;
        private boolean closed = false;
        private int blocksize = BLOCKSIZE;
        private boolean overwrite = false;
        private static final ThreadLocal<DateFormat> dateFmt =
            new ThreadLocal<DateFormat>() {
                @Override
                    protected DateFormat initialValue() {
                        return DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL);
                    }
            };
        
        public Receiver (int port, File targetDir) {
            
            this.port = port;
            this.targetDir = targetDir;

            if (targetDir.exists()) {
                if (! targetDir.isDirectory()){
                    log (Level.SEVERE, "Target directory " + targetDir.getAbsolutePath() + " is an existing file!");
                    System.exit(1);
                }
            }
            
            this.setName("Rcv");
            try{
                log(Level.FINE, "Registering server socket on port " + port);
                log(Level.FINE, "Writing data to " + targetDir.getAbsolutePath());
                serverSocket = new ServerSocket(port);
            } catch (IOException ioe){
                log(Level.SEVERE, "Could not listen on port: "+port, ioe);
                System.exit(-1);
            }
            
        }
        
        public void setBlocksize(int blocksize){
            this.blocksize = blocksize;
        }
        
        public void setOverwrite (boolean overwrite){
            this.overwrite = overwrite;
        }

        public void run() {
            receive ();
        }
        
        private boolean isIpAddressAllowed(Socket clientSocket) {
            if (ipAddresses == null) {
                return true;
            } else {
                String clientAddress = clientSocket.getInetAddress().getHostAddress();
                for (String prefix : ipAddresses) {
                    if (clientAddress.startsWith(prefix.trim())) {
                        return true;
                    }
                }
                log(Level.WARNING, "Connect from " + clientAddress + " not allowed!");
                return false;
            }
        }
        private void receive (){
            while (true) {
                log(Level.WARNING, "=====================================");
                try {
                    if (!closed){
                        log(Level.FINE, "Listening");
                        recvSocket = serverSocket.accept();
                        if (!isIpAddressAllowed (recvSocket)){
                            recvSocket.close();
                            continue;
                        } else {
                            log(Level.FINE, "Connect from " + 
                                    recvSocket.getInetAddress().getHostAddress());
                            log(Level.FINER, "   i.e. "+
                                    "/"+recvSocket.getInetAddress().getHostName());
                        }
                    }
                } catch (SocketException se){
                    log(Level.FINE, "SocketException because of shutdown");
                    return;
                } catch (IOException e) {
                    log(Level.SEVERE, "Accept failed: "+port+ "  ", e);
                    System.exit(-1);
                }
    
                OutputStream sendOs = null;
                InputStream sendIs = null;
                BufferedOutputStream bfos = null;
                long modDate = 0L;
                long fileSize = 0L;
                
                boolean finished = false;
                
                try {
                    OutputStream os = new BufferedOutputStream(recvSocket.getOutputStream());
                    InputStream is = new BufferedInputStream(recvSocket.getInputStream());
                    if (compress) {
                        sendOs = new CompressedBlockOutputStream(os, blocksize, deflaterLevel, 
                                Deflater.DEFAULT_STRATEGY);
                        sendIs = new CompressedBlockInputStream(is);
                    } else { 
                        sendOs = os;
                        sendIs = is;
                    }
                    
                    // send my version
                    writeToStream(sendOs, VERSION);

                    StringBuilder senderForcesOverwrite = new StringBuilder();
                    int c;
                    while ((c = sendIs.read()) != -1 && c != 0){
                        senderForcesOverwrite.append((char)c);
                    }
                    boolean thisOverwrite = overwrite;
                    if (FORCE_OVERWRITE.equals(senderForcesOverwrite.toString())){
                        log(Level.INFO, "Sender forces overwrite: "+senderForcesOverwrite);
                        thisOverwrite = true;
                        createCopy = false;
                    }
                    
                    // 
                    // start here to receive files
                    //

                    long transferStartTime = System.currentTimeMillis();
                    long totalRead = 0;
                    
                    MessageDigest digest = null;
                    try {
                        digest = MessageDigest.getInstance("MD5");
                    } catch (NoSuchAlgorithmException nsae){
                        log(Level.WARNING, "MD5 not available", nsae);
                    }

                    boolean receiving = true;
                    while (receiving){
                        StringBuilder fileName = new StringBuilder();
                        while ((c = sendIs.read()) != -1 && c != 0){
                            fileName.append((char)c);
                        }
                        if (fileName.length() == 0){
                            receiving=false;
                            continue;
                        }
                        if ("FINIS.".equals(fileName.toString())){
                            receiving = false;
                            continue;
                        }
                        log(Level.INFO, "Receiving: "+fileName);
                        File outFile = new File (targetDir, fileName.toString());
                        
                        // receiving file modification date
                        StringBuilder modDateStr = new StringBuilder();
                        while ((c = sendIs.read()) != -1 && c != 0){
                            modDateStr.append((char)c);
                        }
                        try {
                            modDate = Long.parseLong(modDateStr.toString());
                        } catch (NumberFormatException nfe){}
                        
                        // receiving file size
                        StringBuilder sizeStr = new StringBuilder();
                        while ((c = sendIs.read()) != -1 && c != 0){
                            sizeStr.append((char)c);
                        }
                        try {
                            fileSize = Long.parseLong(sizeStr.toString());
                        } catch (NumberFormatException nfe){}
                        
                        if (fileSize == -1) {
                            // this is a directory
                            if (outFile.exists()){
                                log (Level.FINE, "  This is an existent directory");
                                if (!outFile.isDirectory()) {
                                    throw new RuntimeException("Output directory " + outFile.getAbsolutePath() + " is an existing file");
                                }
                                writeToStream(sendOs, EXISTS_WONT_OVERWRITE);
                            } else {
                                log (Level.FINE, "  This is a non-existent directory");
                                if (!outFile.mkdirs()) {
                                    log (Level.SEVERE, "  Directories could not be created for " + outFile.getAbsolutePath());
                                    receiving = false;
                                    writeToStream(sendOs, EXISTS_WONT_OVERWRITE);
                                } else {
                                    log (Level.FINE, "  Created "+outFile.getAbsolutePath());
                                    writeToStream(sendOs, EXISTS_NOT);
                                }
                            }
                            continue;
                        }
        
                        // on Windows, bla.txt equals BLA.TXT, so "exists" has a different semantic
                        /*
                        if (outFile.exists()){
                            if (outFile.getName().equals(outFile.getName().toUpperCase())){
                                File altFileLower = new File(outFile.getParent(), outFile.getName().toLowerCase()+DUPLICATE_SUFFIX);
                                outFile = altFileLower;
                            }
                        }*/
                        if (outFile.exists()) {
                            if (outFile.isDirectory()){
                                //log (Level.SEVERE, "Output file " + outFile.getAbsolutePath() + " is an existing directory");
                                throw new RuntimeException("Output file " + outFile.getAbsolutePath() + " is an existing directory");
                            }
                            if (thisOverwrite) {
                                log(Level.INFO, "Output file " + outFile.getAbsolutePath() + " exists already, will be overwritten");
                                writeToStream(sendOs, EXISTS_WILL_OVERWRITE);
                            } else if (createCopy){
                                log(Level.INFO, "Output file " + outFile.getAbsolutePath() + " exists already, will create copy");
                                writeToStream(sendOs, EXISTS_NOT);
                                outFile = new File(outFile.getParent(), outFile.getName() + DUPLICATE_SUFFIX);
                            } else {
                                log(Level.INFO, "Output file " + outFile.getAbsolutePath() + " exists already, will NOT be overwritten");
                                writeToStream(sendOs, EXISTS_WONT_OVERWRITE);
                                continue;
                            }
                        } else {
                            writeToStream(sendOs, EXISTS_NOT);
                            log(Level.FINER, "Creating dirs for " + outFile.getAbsoluteFile().getParent());
                            if (!outFile.getAbsoluteFile().getParentFile().exists() && 
                                    !outFile.getAbsoluteFile().getParentFile().mkdirs()) {
                                log (Level.SEVERE, "  Directories could not be created.");
                                receiving = false;
                                continue;
                                //throw new RuntimeException("  Directories could not be created."    );
                            }
                        }
                        try {
                            bfos = new BufferedOutputStream(new FileOutputStream (outFile));
                        } catch (FileNotFoundException fnfe){
                            log (Level.SEVERE, "  Output file could not be created: "+outFile.getAbsolutePath());
                            receiving = false;
                            continue;
                            //throw new RuntimeException("Output file could not be created: "+outFile.getAbsolutePath());
                        }
                        log(Level.FINER, "Writing to: "+outFile.getAbsolutePath());
                        byte[] buf = new byte[blocksize];
                        int len = 0;
                        long fileRead = 0;
                        byte[] secondPart = null;
                        digest.reset();
                        StringBuilder md5src = new StringBuilder();
                        while ((len = sendIs.read(buf)) != -1) {
                            fileRead += len;
                            if (fileRead > fileSize){
                                secondPart = new byte[(int)(fileRead-fileSize)];
                                // System.arraycopy compatible with java1.5, Arrays.copyOfRange not
                                System.arraycopy(buf, len-(int)(fileRead-fileSize), secondPart, 0, secondPart.length);
    //                            secondPart = Arrays.copyOfRange(buf, len-(int)(totalRead-fileSize), len);
                                finished = secondPart[secondPart.length-1] == 0;
    //                            byte[] firstPart = Arrays.copyOfRange(buf,0,len-(int)(totalRead-fileSize));
                                byte[] firstPart = new byte[len-(int)(fileRead-fileSize)];
                                System.arraycopy(buf, 0, firstPart, 0, firstPart.length);
                                if (digest != null)
                                    digest.update(firstPart, 0, firstPart.length);
                                bfos.write(firstPart, 0, firstPart.length);
                                break;
                            }
                            if (digest != null)
                                digest.update(buf, 0, len);
                            bfos.write(buf, 0, len);
                        }
                        bfos.flush();
                        totalRead += fileRead;
    
                        // receiving md5 hash
                        //   first add already read bytes
                        if (secondPart != null) {
                            for (byte b : secondPart) {
                                if (b != 0) {
                                    md5src.append((char) b);
                                }
                            }
                        }
                        //log(Level.FINEST, "preliminary md5: " + md5src);
                        //   then read the rest of the file
                        while (!finished && ((c = sendIs.read()) != -1) && (c != 0)){
                            md5src.append((char)c);
                        }
                        log(Level.FINEST, "final md5: " + md5src);
                        
                        String myMd5 = createMd5(digest);
                        writeToStream(sendOs, myMd5);
                        
                        try {
                            if (bfos != null) bfos.close();
                        } catch (IOException ioe1){}

                        // setLastModified() must be called after all file handles to this file have been 
                        // closed, otherwise it doesn't work on Windows (Linux is OK)
                        if (!outFile.setLastModified(modDate)) {
                            log (Level.WARNING, "Last modification date for "+ 
                                    outFile.getAbsoluteFile() + " could not be set");
                        }
                        long checkModDate = outFile.lastModified();
                        log(Level.FINER, "Modification date for "+ outFile.getAbsoluteFile()+":" + 
                                dateFmt.get().format(outFile.lastModified()));
                        if (Math.abs(checkModDate-modDate) > modifyWindow) {
                            log(Level.WARNING, "Last modification dates don't agree. Should be: " + 
                                    dateFmt.get().format(modDate) + "  Diff=" + Math.abs(checkModDate-modDate)+"ms");
                            log(Level.FINER, "Last modification dates don't agree. Should be: " + 
                                    modDate+ " but is " + checkModDate);
                        }
                        
                        checkMd5(digest, md5src, myMd5);
        
                    }
                    // at this point, 1 or more files have been received

                    if (totalRead == 0){
                        log (Level.WARNING, "Nothing transferred.");
                        continue;
                    }
                    
                    long transferEndTime = System.currentTimeMillis();
                    double transferTime = transferEndTime - transferStartTime;
                    log(Level.INFO, String.format ("Received %d bytes in %1.0f ms = %s ", totalRead, transferTime, 
                            formatTransferRate(transferTime, totalRead)));

                    
                } catch (IOException ioe) {
                    log(Level.SEVERE, "echo failed: ",  ioe);
                } finally {
                    try { if (sendOs != null) sendOs.close();} catch (IOException ioe1){}
                    try { if (sendIs != null) sendIs.close();} catch (IOException ioe1){}
                    try { recvSocket.close();} catch (IOException ioe1){}
                    try { if (bfos != null) bfos.close(); } catch (IOException ioe1){}
                }
        
            }   
        }
        
        private String createMd5 (MessageDigest digest){
            String output = "";
            if (digest != null){
                byte[] md5sum = digest.digest();
                BigInteger bigInt = new BigInteger(1, md5sum);
                output = bigInt.toString(16);
            }
            return output;
        }

        private void checkMd5(MessageDigest digest, StringBuilder md5src, String myMd5) {
            if (digest != null){
                log(Level.FINE, "MD5: " + myMd5);
                if (!myMd5.equals(md5src.toString())){
                    log(Level.WARNING, "MD5 hashes don't agree: src="+md5src.toString());
                } else {
                    log(Level.FINE, "MD5 hashes agree.");
                }
            }
        }
        
        private void shutdown(){
            try {
                closed = true;
                log(Level.FINE, "Closing server socket");
                serverSocket.close();
            } catch (IOException ioe){
                log(Level.SEVERE, "Shutdown not successful: ", ioe);
            }
        }
    }
    
    /** Sender functionality. */
    static class Sender {
        private Socket sendSocket = null;
        private int blocksize = BLOCKSIZE;

        private void send(String host, int port, List<File> roots) {

            log(Level.INFO, "=====================================");
            try {
                log(Level.FINE, "Connect to " + host + ":" + port);
                sendSocket = new Socket(host, port);
            } catch (IOException sockEx){
                log(Level.SEVERE, "Could not open port: " + host + ":" + port, sockEx);
                return;
            }
            
            
            MessageDigest digest = null;
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException nsae){
                log(Level.WARNING, "MD5 not available");
            }
            
            OutputStream rcvos = null;
            InputStream rcvis = null;
            BufferedInputStream bfis = null;
            
            byte[] buf = new byte[blocksize];
            try {
                
                OutputStream os = new BufferedOutputStream(sendSocket.getOutputStream());
                InputStream is = new BufferedInputStream(sendSocket.getInputStream());
                if (compress) { 
                    rcvos = new CompressedBlockOutputStream(os, blocksize, deflaterLevel, 
                            Deflater.DEFAULT_STRATEGY);
                    rcvis = new CompressedBlockInputStream(is);
                } else { 
                    rcvos = os;
                    rcvis = is;
                }
                                
                int c;
                StringBuilder rcvVer = new StringBuilder();
                while ((c = rcvis.read()) != -1 && c != 0){
                    rcvVer.append((char)c);
                }
                if (! rcvVer.toString().equals (VERSION)){
                    log(Level.SEVERE, "Receiver's version wrong: " + rcvVer + " vs my " + VERSION);
                    System.exit(1);
                } else {
                    log(Level.FINE, "Receiver sends version " + rcvVer);
                }

                if (overwrite)
                    writeToStream(rcvos, FORCE_OVERWRITE);
                else
                    writeToStream(rcvos, "x");

                //
                // start to send files here //
                //
                ListIterator<File> rootsIt = roots.listIterator();
                while (rootsIt.hasNext()) {
                    File rootDir = rootsIt.next();
                    List<File> dirTree = new ArrayList<File>();
                    if (rootDir.isDirectory()){
                        // copy whole recursive tree
                        dirTree = DirTree.getFiles(rootDir);
                    } else {
                        // only copy the one file
                        dirTree.add(rootDir);
                    }
                    log(Level.FINE, "Root directory: "+rootDir);
                
                    for (File sendFile : dirTree){
                
                        long sendFileSize = sendFile.length();
                        if (sendFile.isDirectory()) sendFileSize = -1;
                        long modDate = sendFile.lastModified();
                        digest.reset();
        
                        bfis = null;
                        try {
                            bfis = new BufferedInputStream(new FileInputStream(sendFile));
                        } catch (FileNotFoundException fnfe) {
                            log(Level.SEVERE, "File not found: "+sendFile.getAbsolutePath(), fnfe);
                            continue;
                        }

                        long transferStartTime = System.currentTimeMillis();
                        log(Level.INFO, "Sending "+sendFile);
        
                        // send file name to the other side
                        String sendFilePath = sendFile.getAbsolutePath().replace('\\', '/');
                        String rootParentPath = rootDir.getParentFile().getAbsolutePath();
                        log (Level.FINEST, "rootParentPath : " + rootParentPath);
                        // BUG with file in root directory: first character is dropped
                        // root dir contains one "/" less
                        // linux: rootparentpath=/
                        // sendFilePath=/.autofsck
                        // rootlessPath=autofsck
                        // windows: rootparentpath=h:\
                        // sendFilePath=h:/bla
                        // rootlessPath=la
                        log (Level.FINEST, "sendFilePath: " + sendFilePath);
                        String rootlessPath = sendFilePath.substring(rootParentPath.length(), 
                                sendFilePath.length());
                        log (Level.FINEST, "Rootless path: " + rootlessPath);
                        rootlessPath = rootlessPath.replaceFirst("/", "");
                        log (Level.FINEST, "Rootless path: " + rootlessPath);
                        writeToStream(rcvos, rootlessPath, false);
        
                        int len = 0;
                        // send file modification date to the other side
                        log(Level.FINE, "modDate " + modDate);
                        writeToStream(rcvos, String.valueOf(modDate), false);
                        // send file size to the other side
                        log(Level.FINE, "Sending " + sendFileSize + " bytes");
                        writeToStream(rcvos, String.valueOf(sendFileSize), true);
        
                        StringBuilder existsOnOtherSide = new StringBuilder();
                        while ((c = rcvis.read()) != -1 && c != 0){
                            existsOnOtherSide.append((char)c);
                        }
                        log(Level.FINEST, "Exists on other side: " + existsOnOtherSide);
                        if (existsOnOtherSide.toString().equals(EXISTS_WONT_OVERWRITE)){
                            log(Level.WARNING, "File exists on other side, not sending.");
                            continue;
                        }
        
                        // for directories, stop here, nothing to transmit; the receiver will just mkdir
                        if (sendFileSize == -1) continue;
                        
                        // this is the send loop
                        int sentBytes = 0;
                        int tickFactor = (int)(sendFileSize / progressTicks);
                        int nextTickAt = tickFactor;
                        int ticksPrinted = 0;
                        if (printProgressBar) {
                            log(Level.INFO, String.format("Each tick is %s", formatKiBMiBGiB(tickFactor)));
                            System.out.print("[");
                        }
                        while ((len = bfis.read(buf)) != -1) {
                            if (digest != null)
                                digest.update(buf, 0, len);
                            rcvos.write(buf, 0, len);
                            sentBytes += len;
                            if (printProgressBar && (sentBytes >= nextTickAt)){
                                System.out.print(TICK_SYMBOL);
                                nextTickAt += tickFactor;
                                ticksPrinted++;
                            }
                        }
                        if (printProgressBar) {
                            for (int i = ticksPrinted; i < progressTicks; i++) System.out.print(TICK_SYMBOL);
                            System.out.print("] ");
                        }
                        if (printProgressBar) {
                            System.out.println();
                        }
                        long transferEndTime = System.currentTimeMillis();
                        double transferTime = transferEndTime - transferStartTime;
                        if (printProgressBar) {
                            log(Level.INFO, String.format ("Sent %d bytes in %1.0f ms = %s ", sentBytes, transferTime, 
                                    formatTransferRate(transferTime, sentBytes)));
                        } else {
                            log(Level.FINE, String.format ("Sent %d bytes in %1.0f ms = %s ", sentBytes, transferTime, 
                                    formatTransferRate(transferTime, sentBytes)));
                        }

                        //rcvos.flush();
        
                        // send file md5 hash to the other side
                        String md5 = "";
                        if (digest != null){
                            byte[] md5sum = digest.digest();
                            BigInteger bigInt = new BigInteger(1, md5sum);
                            md5 = bigInt.toString(16);
                            log(Level.FINER, "MD5: " + md5);
                        }
                        writeToStream(rcvos, md5);
                        
                        // expecting receiver to bounce md5 sum
                        StringBuilder rcvmd5 = new StringBuilder();
                        while ((c = rcvis.read()) != -1 && c != 0){
                            rcvmd5.append((char)c);
                        }
                        if (rcvmd5.length() == 0){
                            log(Level.SEVERE, "An error has occured, probably connection closed, giving up.");
                            throw new RuntimeException ("An error has occured, probably connection closed, giving up.");
                        }
                        if (! rcvmd5.toString().equals (md5)){
                            log(Level.SEVERE, "Receiver advises wrong md5 sum: " + rcvmd5 + " vs my " + md5);
                        } else {
                            log(Level.FINE, "Receiver advises correct md5 sum");
                        }

                        try { 
                            if (bfis != null) bfis.close();
                        } catch (IOException ioe1){
                            //ignore
                        }
                    }
                }
                
                writeToStream(rcvos, "FINIS.");
                
                // at this point, 1 or more files have been sent
    
            } catch (IOException ioe){
                log(Level.SEVERE, "", ioe);
            } finally {
                try { 
                    if (bfis != null) bfis.close();
                } catch (IOException ioe1){}
                try { 
                    if (rcvos != null) rcvos.close();
                    sendSocket.close();
                } catch (IOException ioe1){}
                try { 
                    sendSocket.close();
                } catch (IOException ioe1){}
            }
    
        }
        
        public void setBlocksize(int blocksize){
            this.blocksize = blocksize;
        }

    }

    /** Custom logger. */
    static String dolog (Level level, String s){
        StringBuilder output = new StringBuilder()
        .append("[")
        .append(level).append('|')
        .append(Thread.currentThread().getName()).append('|')
        .append(dateFormat.get().format(System.currentTimeMillis()))
        .append("]: ")
        .append(s).append(' ')
        .append(System.getProperty("line.separator"));
        return output.toString();
    }
    
    static String dolog (Level level, String s, Exception e){
        return dolog(level, s) + " " + e.toString();
    }
    
    static void log (Level level, String s){
        if (level.intValue() >= logLevel.intValue()){
            if (level.intValue() >= Level.WARNING.intValue()){
                System.err.print(dolog(level, s));
            } else {
                System.out.print(dolog(level, s));
            }
        }
    }
 
    static void log (Level level, String s, Exception e){
        if (level.intValue() >= logLevel.intValue()){
            System.err.println(dolog(level, s, e));
            if (logLevel.intValue() <= Level.FINE.intValue()){
                for (StackTraceElement ste : e.getStackTrace()){
                    System.err.print(String.format("         %s : %n", ste.toString()));
                }
            }
        }
    }

    private static void usage() {
        log (Level.SEVERE, "Usage: [[-t <output dir>] || ");
        log (Level.SEVERE, "  [-help] this help");
        log (Level.SEVERE, "  [--help] this help");
        log (Level.SEVERE, "  [-h <target host>]]  (sender mode only); if not provided, localhost is assumed");
        log (Level.SEVERE, "  [-p <port>]");
        log (Level.SEVERE, "  [-B <blocksize in bytes>]");
        log (Level.SEVERE, "  [-o] overwrite existing files (sender overrides reader; cancels -O)");
        log (Level.SEVERE, "  [-O] create copy if file exists (cancels -o; receiver mode only)");
        log (Level.SEVERE, "  [-z] compress (zip) network stream");
        log (Level.SEVERE, "      if used, MUST be used on both sides, otherwise OutOfMemoryError on sending side"); 
        log (Level.SEVERE, "  [-Z <1|5|9>]  compress level (1:fast, 5:default, 9:high compression; default 5)"); 
        log (Level.SEVERE, "  [-l SEVERE|WARNING|INFO|FINE|FINER|FINEST]");
        log (Level.SEVERE, "  [-i <ip address[,ip address]>]  -> allowed ip addresses");
        log (Level.SEVERE, "  [-v  print version]");
        log (Level.SEVERE, "  [-mod  <milliseconds>]   -> modification time window to test last modification times; default 1000ms, because Windows only has a 1000ms resolution");
        log (Level.SEVERE, "  [-b[<number of ticks>]   -> print progress bar; num ticks optional, default 40");
        log (Level.SEVERE, "  [-cyg]   -> treat paths as cygwin paths and convert to windows paths for java's benefit");
        log (Level.SEVERE, "  [<files|dir> [<files|dir> ...]]  (sender mode only)");
        log (Level.SEVERE, "If <files|dir> is a directory, it will be copied recursively.");
        log (Level.SEVERE, "Examples:");
        log(Level.SEVERE, "  Sending: java -jar xfer.jar [-p port] [-h host] [-l FINE (logging)] <file(s)>");
        log(Level.SEVERE, "  Receiving: java -jar xfer.jar [-p port] [-l FINE (logging)] [-t targetDir]");
        
        // TODO: not yet ; log (Level.SEVERE, "Semantics of dir like in rsync (adir vs adir/).");
    }

    public static void main (String[] args){
        int port = PORT;
        File targetDir = new File(".");
        String host = "";
        List<File> sendRoots = new ArrayList<File>();
        boolean useCygpaths = false;
        
        dateFormat.get().setTimeZone(TimeZone.getTimeZone("UTC"));
        
        // parse command line options
        int a = 0;
        while(a < args.length){
            String opt = args[a];
            if (opt.startsWith("-")){
                // first options without argument
                if (opt.startsWith("-o")){ // overwrite
                    overwrite = true;
                    createCopy = false;
                    a++;
                    log (Level.CONFIG, "Setting overwrite");
                } else if (opt.startsWith("-O")){ // create copy with added suffix
                    createCopy = true;
                    overwrite = false;
                    a++;
                    log (Level.CONFIG, "Setting create copy with added suffix if exists, cancels set overwrite");
                } else if (opt.startsWith("-z")){ // zip
                        compress = true;
                        a++;
                        log (Level.CONFIG, "Setting compression");
                } else if (opt.startsWith("-v")){ // version
                    log(Level.SEVERE, "This is version " + VERSION);
                    a++;
                } else if (opt.startsWith("-b")){ // progress bar
                    printProgressBar = true;
                    if (opt.length() > 2){
                        try { 
                            progressTicks = Integer.parseInt(opt.substring(2));
                        } catch (NumberFormatException nfe){
                            log(Level.SEVERE, "Could not parse progress bar option "+opt);
                            printProgressBar = false;
                        }
                    }
                    if (printProgressBar) log(Level.CONFIG, "Setting progress bar to "+progressTicks
                            + " ticks");
                    a++;
                } else if (opt.startsWith("-cyg")){ // cygwin paths
                    log(Level.SEVERE, "Using cygwin paths");
                    useCygpaths = true;
                    a++;
                } else if (opt.equals("-help") || opt.equals("--help")){ 
                    usage();
                    System.exit(0);
                } else {
                    // now options with 1 argument 
                    a++;
                    if (a >= args.length){
                        //usage();
                        continue;
                    }
                    String parm = args[a];
                    if (opt.startsWith("-p")){ // Port
                        try { 
                            port = Integer.parseInt(parm);
                        } catch (NumberFormatException nfe){
                            log(Level.SEVERE, "Could not parse port "+parm);
                            port = PORT;
                        }
                        log (Level.CONFIG, "Setting port to " + port);
                        a++;
                    } else if (opt.startsWith("-t")){ // Target Directory on receiver
                        targetDir = new File(parm);
                        a++;
                        log (Level.CONFIG, "Setting target dir to " + targetDir.getAbsolutePath());
                    } else if (opt.equals("-h")){ // Receiver Host
                        host = parm;
                        a++;
                        log (Level.CONFIG, "Setting host to " + host);
                    } else if (opt.startsWith("-i")){ // allowed IP addresses that can connect
                        ipAddresses = parm.split(",");
                        a++;
                        log (Level.CONFIG, "Accepting connections from ip address ranges: " + Arrays.toString(ipAddresses));
                    } else if (opt.startsWith("-l")){ // log level
                        if (parm.startsWith("FINEST")){
                            logLevel = Level.FINEST;
                        } else if (parm.startsWith("FINER")){
                            logLevel = Level.FINER;
                        } else if (parm.startsWith("FINE")){
                            logLevel = Level.FINE;
                        } else if (parm.startsWith("CONFIG")){
                            logLevel = Level.CONFIG;
                        } else if (parm.startsWith("INFO")){
                            logLevel = Level.INFO;
                        } else if (parm.startsWith("WARNING")){
                            logLevel = Level.WARNING;
                        } else if (parm.startsWith("SEVERE")){
                            logLevel = Level.SEVERE;
                        }
                        log (Level.CONFIG, "Setting log level to " + logLevel.getName());
                        a++;
                    } else if (opt.startsWith("-B")){ // block size
                        try { 
                            blocksize = Integer.parseInt(parm);
                        } catch (NumberFormatException nfe){
                            log(Level.SEVERE, "Could not parse block size "+parm);
                            blocksize = BLOCKSIZE;
                        }
                        a++;
                        log (Level.CONFIG, "Setting block size to " + blocksize);
                    } else if (opt.startsWith("-Z")){ // compression mode (1:fast, 5:default, 9:high compression)
                        try { 
                            deflaterLevel = Integer.parseInt(parm);
                        } catch (NumberFormatException nfe){
                            log(Level.SEVERE, "Could not parse deflater level "+parm);
                            deflaterLevel = Deflater.DEFAULT_COMPRESSION;
                        }
                        switch (deflaterLevel){
                            case 0: case 1: deflaterLevel = 1; break;
                            case 2: case 3: case 4: case 5: deflaterLevel = 5; break;
                            case 6: case 7: case 8:
                            case 9: deflaterLevel = 9; break;
                            default: deflaterLevel = 9; break;
                        }
                        a++;
                        log (Level.CONFIG, "Setting compress level to " + deflaterLevel);
                    } else if (opt.startsWith("-mod")){ // modifyWindow
                        try { 
                            modifyWindow = Long.parseLong(parm);
                        } catch (NumberFormatException nfe){
                            log(Level.SEVERE, "Could not parse modify window option "+parm);
                        }
                        a++;
                    } else if (opt.startsWith("-")){
                        log (Level.WARNING, "Unknown option: " + opt);
                        usage();
                        System.exit(1);
                    }
                }
            } else { // files to send
                String f = null;
                try {
                    f = args[a++];
                    if (useCygpaths) {
                        f = cyg2win(f);
                        log(Level.FINE, "Converted root to " + f);
                    }
                    if (!"".equals(f))
                        sendRoots.add(new File(f).getCanonicalFile());
                } catch (IOException ioe) {
                    log (Level.SEVERE, "Could not create canonical name of " + f);
                }
            }
        }
        
        log(Level.CONFIG, "This is version " + VERSION);
        new Xfer2();
        
        if (sendRoots.size() == 0){
            log(Level.INFO, "Starting in receiving mode.");
            final Receiver rcvThread = new Receiver(port, targetDir);
            rcvThread.setBlocksize(blocksize);
            rcvThread.setOverwrite(overwrite);
            rcvThread.start();

            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    rcvThread.shutdown();
                }
             });

        } else {
            log(Level.FINE, "Starting in sending mode.");
        
            Sender sender = new Sender();
            sender.setBlocksize(blocksize);
            sender.send(host, port, sendRoots);
        }
    }
}
