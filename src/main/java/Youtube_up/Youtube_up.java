package Youtube_up;
/**
 * Created by kpeyanski on 21.10.2015
 */

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import com.google.common.collect.Lists;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class Youtube_up {

    static Integer alreadyInsertedCounter = 0;      // For counting parsed files, but already uploaded in DB
    static Integer successfullyInsertedCounter = 0; // For counting successfully uploaded & inserted files
    private static String dbNAME = "UploadedFiles.db";      // Local DB name that should be used for insert and select
    private static Long fileSize = Long.valueOf(51200);     // You can set file size threshold here. Files below that size will not be uploaded
    static int count = 0;                           // re-try-catch counters
    static int maxTries = 5;                        // max tries if exception happens
    static int sleepTime = 30;                      // hom many seconds to wait before retry
    private static YouTube youtube;                 // Global instance of Youtube object to make all API requests.
    private static String VIDEO_FILE_FORMAT = "video/*";  //Global instance of the format used for the video being uploaded (MIME type).

    // URL - http://www.rgagnon.com/javadetails/java-0416.html
    // a byte array to a HEX string
    public static String getMD5Checksum(String filename) throws Exception {
        byte[] b = createChecksum(filename);
        String result = "";
        for (int i=0; i < b.length; i++) {
            result +=
                    Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 );
        }
        return result;
    }

    // used by the getMD5Checksum method
    public static byte[] createChecksum(String filename) throws
            Exception
    {
        InputStream fis =  new FileInputStream(filename);

        byte[] buffer = new byte[1024];
        MessageDigest complete = MessageDigest.getInstance("MD5");
        int numRead;
        do {
            numRead = fis.read(buffer);
            if (numRead > 0) {
                complete.update(buffer, 0, numRead);
            }
        } while (numRead != -1);
        fis.close();
        return complete.digest();
    }

    // Recursive display of the directory contents with file extension filter and
    public static void displayDirectoryContents(File dir) {
        List<String> scopes = Lists.newArrayList("https://www.googleapis.com/auth/youtube.upload");
        try {
            File[] files = dir.listFiles();
            for (File file : files) {
                if (file.isDirectory()) {
                    displayDirectoryContents(file);
                } else {
                    Path filePath = file.toPath();      // get basic file attribute of the file
                    BasicFileAttributes fileAttr = Files.readAttributes(filePath, BasicFileAttributes.class);

                    // file extension filter is here also file size should be above 512004bytes(50Kb)
                    if ((fileAttr.size() > fileSize) &&         // if file size is bigger than fileSize var
                            (file.getCanonicalPath().toLowerCase().endsWith(".wmv") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".flv") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".f4v") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".mov") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".3gp") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".avi") ||
                                    file.getCanonicalPath().toLowerCase().endsWith(".mp4"))) {

                        System.out.print(file.getCanonicalPath() + " - Found; Size - " + fileAttr.size());

                        try {
                        // if there is such MD5 in DB don't upload & insert it
                        if (checkMD5(getMD5Checksum(file.getCanonicalPath()))) {
                            System.out.println(" : Video already in DB!");

                            ++alreadyInsertedCounter;

                        // if there is no such MD5 in DB - upload it to YouTube and insert the record in the DB
                        } else {

                            // upload start
                            try {

                                // Authorize the request.
                                Credential credential = Auth.authorize(scopes, "uploadvideo");

                                // This object is used to make YouTube Data API requests.
                                youtube = new YouTube.Builder(Auth.HTTP_TRANSPORT, Auth.JSON_FACTORY, credential).setApplicationName(
                                        "Youtube_up").build();

                                System.out.println("New video! Youtube_up uploading...");


                                // Add extra information to the video before uploading.
                                Video videoObjectDefiningMetadata = new Video();

                          /*
                           * Set the video to public, so it is available to everyone (what most people want). This is
                           * actually the default, but I wanted you to see what it looked like in case you need to set
                           * it to "unlisted" or "private" via API.
                           */
                                VideoStatus status = new VideoStatus();
                                status.setPrivacyStatus("private");
                                videoObjectDefiningMetadata.setStatus(status);

                                // We set a majority of the metadata with the VideoSnippet object.
                                VideoSnippet snippet = new VideoSnippet();

                          /*
                           * The Calendar instance is used to create a unique name and description for test purposes, so
                           * you can see multiple files being uploaded. You will want to remove this from your project
                           * and use your own standard names.
                           */
                                Calendar cal = Calendar.getInstance();

                                snippet.setTitle(file.getName() + " " + cal.getTime());
                                snippet.setDescription(
                                        "Video uploaded via Youtube_up" + "on " + cal.getTime());

                                // Set keywords.
                                List<String> tags = new ArrayList<String>();
                                tags.add("Youtube_up");
                                tags.add(fileAttr.creationTime().toString()); //movie creation date
                                tags.add("Auto upload");
                                tags.add("YouTube Data API V3");
                                snippet.setTags(tags);

                                // Add the completed snippet object to the video resource.
                                videoObjectDefiningMetadata.setSnippet(snippet);

                                InputStreamContent mediaContent = new InputStreamContent(
                                        VIDEO_FILE_FORMAT, new BufferedInputStream(new FileInputStream(file)));
                                mediaContent.setLength(file.length());

                                // Insert the video. The command sends three arguments. The first
                                // specifies which information the API request is setting and which
                                // information the API response should return. The second argument
                                // is the video resource that contains metadata about the new video.
                                // The third argument is the actual video content.
                                YouTube.Videos.Insert videoInsert = youtube.videos()
                                        .insert("snippet,statistics,status", videoObjectDefiningMetadata, mediaContent);

                                // Set the upload type and add an event listener.
                                MediaHttpUploader uploader = videoInsert.getMediaHttpUploader();

                                // Indicate whether direct media upload is enabled. A value of
                                // "True" indicates that direct media upload is enabled and that
                                // the entire media content will be uploaded in a single request.
                                // A value of "False," which is the default, indicates that the
                                // request will use the resumable media upload protocol, which
                                // supports the ability to resume an upload operation after a
                                // network interruption or other transmission failure, saving
                                // time and bandwidth in the event of network failures.
                                uploader.setDirectUploadEnabled(false);

                                MediaHttpUploaderProgressListener progressListener = new MediaHttpUploaderProgressListener() {
                                    public void progressChanged(MediaHttpUploader uploader) throws IOException {
                                        switch (uploader.getUploadState()) {
                                            case INITIATION_STARTED:
                                                System.out.println("Initiation Started");
                                                break;
                                            case INITIATION_COMPLETE:
                                                System.out.println("Initiation Completed");
                                                break;
                                            case MEDIA_IN_PROGRESS:
                                                System.out.println("Upload in progress");
                                                System.out.println("Upload percentage: " + uploader.getProgress());
                                                break;
                                            case MEDIA_COMPLETE:
                                                System.out.println("Upload Completed!");
                                                break;
                                            case NOT_STARTED:
                                                System.out.println("Upload Not Started!");
                                                // insert INFO msg into Events table
                                                String msgToInsert = "Upload Not Started!";
                                                if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                                    System.out.println("Successfully inserted the following ERROR msg into EVENTS -- "+msgToInsert);
                                                }
                                                break;
                                        }
                                    }
                                };
                                uploader.setProgressListener(progressListener);

                                // Call the API and upload the video.
                                Video returnedVideo = videoInsert.execute();

                                // Print out returned results.
                                System.out.println("\n================== Returned Video ==================\n");
                                System.out.println("  - Id: " + returnedVideo.getId());
                                System.out.println("  - Title: " + returnedVideo.getSnippet().getTitle());
                                System.out.println("  - Tags: " + returnedVideo.getSnippet().getTags());
                                System.out.println("  - Privacy Status: " + returnedVideo.getStatus().getPrivacyStatus());
                                System.out.println("  - Video Count: " + returnedVideo.getStatistics().getViewCount());

                                // insert INFO msg into Events table
                                String msgToInsert = ("Video - Title: " + returnedVideo.getSnippet().getTitle()) +
                                        " - Tags: " + returnedVideo.getSnippet().getTags() + " - Privacy Status: " +
                                         returnedVideo.getStatus().getPrivacyStatus();
                                if (insertIntoEventsTable("INFO", msgToInsert)) {
                                    System.out.println(msgToInsert);
                                }

                            } catch (GoogleJsonResponseException e) {
                                System.err.println("GoogleJsonResponseException code: " + e.getDetails().getCode() + " : "
                                        + e.getDetails().getMessage());
                                e.printStackTrace();

                                // insert ERROR msg into Events table
                                String msgToInsert = "GoogleJsonResponseException code: " + e.getDetails().getCode() + " : "
                                        + e.getDetails().getMessage() + " : Failed to insert - " + file.getCanonicalPath() +
                                        " : Retries = " + count;
                                if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                    System.out.println(msgToInsert);
                                }

                                // Check if maxTries is reached else sleep for sleepTime seconds
                                if (count++ == maxTries) {
                                    if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                        System.out.println(msgToInsert);
                                        System.exit(-1);
                                    }
                                else {
                                        Thread.sleep(1000*sleepTime);
                                    }
                                }

                            } catch (IOException e) {
                                System.err.println("IOException: " + e.getMessage()+ " : Failed to insert - " +
                                        file.getCanonicalPath() + " : Retries = " + count);
                                e.printStackTrace();
                                String msgToInsert = "IOException:  " + e.getMessage() + " : Failed to insert - " +
                                        file.getCanonicalPath() + " : Retries = " + count;
                                if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                    System.out.println(msgToInsert);
                                }

                                // Check if maxTries is reached else sleep for sleepTime seconds
                                if (count++ == maxTries) {
                                    if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                        System.out.println(msgToInsert);
                                        System.exit(-1);
                                    }
                                    else {
                                        Thread.sleep(1000*sleepTime);
                                    }
                                }
                            } catch (Throwable t) {
                                System.err.println("Throwable: " + t.getMessage() + " : Failed to insert - " +
                                        file.getCanonicalPath() + " : Retries = " + count);
                                t.printStackTrace();
                                String msgToInsert = "Throwable: " + t.getMessage() + " : Failed to insert - " +
                                        file.getCanonicalPath() + " : Retries = " + count;
                                if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                    System.out.println(msgToInsert);
                                }
                                // Check if maxTries is reached else sleep for sleepTime seconds
                                if (count++ == maxTries) {
                                    if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                        System.out.println(msgToInsert);

                                    }

                                    msgToInsert = "Successfully Inserted New Videos = " + successfullyInsertedCounter;
                                    if (insertIntoEventsTable("INFO", msgToInsert)) {
                                        System.out.println(msgToInsert);
                                    }

                                    msgToInsert = "Already Inserted Videos = " + alreadyInsertedCounter;
                                    if (insertIntoEventsTable("INFO", msgToInsert)) {
                                        System.out.println(msgToInsert);
                                    }

                                    msgToInsert = "-= END of RUN =-  due to exception";
                                    if (insertIntoEventsTable("ERROR", msgToInsert)) {
                                        System.out.println(msgToInsert);
                                        System.exit(-1);
                                    }

                                    else {
                                        Thread.sleep(1000*sleepTime);
                                    }
                                }
                            }
                            ++successfullyInsertedCounter;
                            // insert the Video record in the local DB
                            if (insertIntoUploadsTable(file.getCanonicalPath(), getMD5Checksum(file.getCanonicalPath()))){
                                System.out.println("  file: " + file.getCanonicalPath() + " ; MD5 - "
                                        + getMD5Checksum(file.getCanonicalPath())+" successfully inserted into DB");
                            }


                        }
                        // upload end


                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("got here 1");  // TODO: DELETE ME AFTER SUCCESSFUL RE-TRY-CATCH IMPLEMENTATION
                    }

                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("got here 2");  // TODO: DELETE ME AFTER SUCCESSFUL RE-TRY-CATCH IMPLEMENTATION
        }
    }


    // Return TRUE if there is such MD5 in the DB - @param1 DB name, @param2 MD5 string
    public static Boolean checkMD5( String md5 ) {
        Connection c = null;
        Statement stmt = null;
        Boolean dbMD5 = false;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:"+dbNAME);
            c.setAutoCommit(false);
            stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery( "SELECT * FROM UPLOADS WHERE MD5='" + md5 +"';" );
            while ( rs.next() ) {
                String MD5result = rs.getString("md5");

                //if (MD5result != null) dbMD5 = true;
                dbMD5 = MD5result != null;
            }
            rs.close();
            stmt.close();
            c.close();
        } catch ( Exception e ) {
            System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            System.exit(0);
        }
        return dbMD5;
    }

    // Return TRUE if 'filename' & 'MD5' are successfully inserted
    public static Boolean insertIntoUploadsTable(String filename, String MD5insert)
    {
        Connection c = null;
        Statement stmt = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:"+dbNAME);
            c.setAutoCommit(false);
            System.out.println(dbNAME +" Database Opened successfully for insert");
            stmt = c.createStatement();
            String sql = "INSERT INTO UPLOADS (FILENAME,MD5) " +
                    "VALUES ('" + filename + "', '" + MD5insert + "' );";
            stmt.executeUpdate(sql);
            stmt.close();
            c.commit();
            c.close();
        } catch ( Exception e ) {
            System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            System.exit(0);
        }
        return true;
    }

    // Return TRUE if 'type' & 'description' are successfully inserted into EVENTS
    public static Boolean insertIntoEventsTable(String type, String description)
    {
        Connection c = null;
        Statement stmt = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:"+dbNAME);
            c.setAutoCommit(false);
           // System.out.println(dbNAME +" Database Opened successfully for insert");
            stmt = c.createStatement();
            String sql = "INSERT INTO EVENTS (TYPE,DESCRIPTION) " +
                    "VALUES ('" + type + "', '" + description + "');";
            stmt.executeUpdate(sql);
            stmt.close();
            c.commit();
            c.close();
        } catch ( Exception e ) {
            System.err.println( e.getClass().getName() + ": " + e.getMessage() );
            System.exit(0);
        }
        return true;
    }

    // returns true if tables UPLOADS & EVENTS are successfully created
    public static boolean dbsCreate() {
        Connection c = null;
        Statement stmt = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:"+dbNAME);
            stmt = c.createStatement();

            // UPLOADS TABLE CREATE TABLE SQL
            String uploadsTable = "CREATE TABLE UPLOADS " +
                    " (FILENAME           TEXT     NOT NULL, " +
                    " UPLOADED_AT    DEFAULT (DATETIME(CURRENT_TIMESTAMP, 'LOCALTIME')), " +
                    " MD5            CHAR(32) NOT NULL)";

            // EVENTS TABLE CREATE TABLE SQL
            String eventsTable = "CREATE TABLE EVENTS \n" +
                    " (TYPE           TEXT     NOT NULL, \n" +
                    " TIMECREATED    DEFAULT (DATETIME(CURRENT_TIMESTAMP, 'LOCALTIME')), \n" +
                    " DESCRIPTION           TEXT     NOT NULL)";

            stmt.addBatch(uploadsTable);
            stmt.addBatch(eventsTable);
            stmt.executeBatch();
            stmt.close();
            c.close();
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);

        }
        System.out.println("UPLOADS & EVENTS Tables created successfully");
        return true;


    }


    static void usage() {
        System.err.println("usage: java -jar Youtube_up dir");
        System.exit(-1);
    }

    public static void main(String[] args) throws IOException {

        /*File currentDir = new File("."); // current directory
        displayDirectoryContents(currentDir);*/

        // used to measure the running time of the program
        long startTime = System.currentTimeMillis();

        // check if the DB file exists if not then it's created
        File dbFile = new File (dbNAME);
        if (!dbFile.exists()) {
            dbsCreate();
        }

        // parse input arguments
        if (args.length == 0 || args.length > 1) {
            usage();
        }
        int dirArg = 0;

        // insert INFO msg into Events table
        String msgToInsert = "-= START of RUN =- ";
        if (insertIntoEventsTable("INFO", msgToInsert)) {
            System.out.println(msgToInsert);
        }

        File currentDir = new File(args[dirArg]);
        displayDirectoryContents(currentDir);

        msgToInsert = "Successfully Inserted New Videos = " + successfullyInsertedCounter;
        if (insertIntoEventsTable("INFO", msgToInsert)) {
            System.out.println(msgToInsert);
        }

        msgToInsert = "Already Inserted Videos = " + alreadyInsertedCounter;
        if (insertIntoEventsTable("INFO", msgToInsert)) {
            System.out.println(msgToInsert);
        }

        // used to measure the running time of the program
        long endTime = System.currentTimeMillis();
        NumberFormat formatter = new DecimalFormat("#0.00");
        String runTime = formatter.format((endTime - startTime) / 1000d);

        msgToInsert = "-= END of RUN =- Execution Time: " + runTime + " seconds";
        if (insertIntoEventsTable("INFO", msgToInsert)) {
            System.out.println(msgToInsert);
        }


    }

}