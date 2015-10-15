import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
* Library to recursively list files.
*  
* @author amhlaobh@users.noreply.github.com
*/
public final class DirTree {

  /**
  * Command line use.
  * @param args - <tt>args[0]</tt> seed directory to start from
  */
  public static void main(String[] args) throws FileNotFoundException {
    if (args.length == 0){
        return;
    }
    File seedDir = new File(args[0]);
    List<File> files = DirTree.getFiles(seedDir);

    //print out all file names, in the the order of File.compareTo()
    for(File file : files ){
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(file.lastModified());
        Date d = cal.getTime();
        System.out.printf("%10d %tH:%tM:%tS %Td.%Tm.%TY %s%n",
                file.length(), 
                d,d,d,d,d,d, 
                file.toString());
    }
  }
  
  /**
  * Recursively walk a directory tree and return a list of all
  * Files found.
  *
  * @param seedDir - the existing directory to start recursing from 
  */
    public static List<File> getFiles(File seedDir) {
        List<File> result =new ArrayList<File>();
        try {
            result = getFileList(seedDir);
        } catch (FileNotFoundException fnfe) {
            System.err.println("Starting directory could not be found: " + seedDir);
        }
        Collections.sort(result);
        return result;
    }

    private static List<File> getFileList(File seedDir)
            throws FileNotFoundException {
        List<File> result = new ArrayList<File>();
        if (!validateDirectory(seedDir)){
            return result;
        }
        File[] filesAndDirs = seedDir.listFiles();
        if (filesAndDirs == null) {
            return result;
        }
        List<File> filesDirsList = Arrays.asList(filesAndDirs);
        for (File file : filesDirsList) {
            if (file.isFile())
                result.add(file); // only add leaf files
              if (file.isDirectory()) {
                // do recursion
                List<File> deeperList = getFileList(file);
                result.add(file);
                result.addAll(deeperList);
            }
        }
        return result;
    }

    /**
     * Directory is valid if it exists, does not represent a file, and can be
     * read.
     */
    private static boolean validateDirectory(File directory)
            throws FileNotFoundException {
        boolean ok = true;
        if (directory == null) {
            System.err.println("Directory should not be null.");
            ok = false;
        } else if (!directory.exists()) {
            System.err.println("Directory does not exist: " + directory);
            ok = false;
        } else if (!directory.isDirectory()) {
            System.err.println("Is not a directory: " + directory);
            ok = false;
        } else if (!directory.canRead()) {
            System.err.println("Directory cannot be read: " + directory);
            ok = false;
        }
        
        return ok;
    }
} 
