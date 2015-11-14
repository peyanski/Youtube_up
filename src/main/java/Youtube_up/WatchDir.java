
package Youtube_up;


/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Oracle nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * Created by kpeyanski on 15.10.2015 „..
 * URL - http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java
 * more info - https://docs.oracle.com/javase/tutorial/essential/io/notification.html
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;


/**
 * Example to watch a directory (or tree) for changes to files.
 */


public class WatchDir {


    // Global instance of Youtube object to make all API requests.
    private static YouTube youtube;

    // Global instance of the format used for the video being uploaded (MIME type).
    private static String VIDEO_FILE_FORMAT = "video*/";
    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    private final boolean recursive;
    private boolean trace = false;

    /**
     * Creates a WatchService and registers the given directory
     */

    WatchDir(Path dir, boolean recursive) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey, Path>();
        this.recursive = recursive;

        if (recursive) {
            System.out.format("Scanning %s ...\n", dir);
            registerAll(dir);
            System.out.println("Done.");
        } else {
            register(dir);
        }

        // enable trace after initial registration
        this.trace = true;
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    static void usage() {
        System.err.println("usage: java WatchDir [-notrecursive] dir");
        System.exit(-1);
    }

    public static void main(String[] args) throws IOException {
        // parse arguments
        if (args.length == 0 || args.length > 2)
            usage();
        boolean recursive = true;
        int dirArg = 0;
        if (args[0].equals("-notrecursive")) {
            if (args.length < 2)
                usage();
            recursive = false;
            dirArg++;
        }

        // register directory and process its events
        Path dir = Paths.get(args[dirArg]);
        new WatchDir(dir, recursive).processEvents();
    }

    /**
     * Register the given directory with the WatchService
     */

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */

    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Process all events for keys queued to the watcher
     */

    void processEvents() {
        for (; ; ) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                // print out event
                System.out.format("%s: %s\n", event.kind().name(), child);

                List<String> scopes = Lists.newArrayList("https://www.googleapis.com/auth/youtube.upload");

                // file extension filter. If you want you can add more video extensions here
                String lowercaseName = name.toString().toLowerCase();
                if (lowercaseName.endsWith(".webm") || lowercaseName.endsWith(".flv")
                        || lowercaseName.endsWith(".f4v") || lowercaseName.endsWith(".mov")
                        || lowercaseName.endsWith(".3gp") || lowercaseName.endsWith(".avi")
                        || lowercaseName.endsWith(".mp4")) {

                    try {
                        // Authorization.
                        Credential credential = Auth.authorize(scopes, "uploadvideo");

                        // YouTube object used to make all API requests.
                        youtube = new YouTube.Builder(Auth.HTTP_TRANSPORT, Auth.JSON_FACTORY, credential).setApplicationName(
                                "Youtube_up").build();

                        // We get the user selected local video file to upload.
                        //  File videoFile = getVideoFromUser();
                        File videoFile = child.toFile();
                        System.out.println("You chose " + videoFile + " to upload.");

                        // Add extra information to the video before uploading.
                        Video videoObjectDefiningMetadata = new Video();


                            /*
                           * Set the video to public, so it is available to everyone (what most people want). This is
                           * actually the default, but I wanted you to see what it looked like in case you need to set
                           * it to "unlisted" or "private" via API.
                           */

                        VideoStatus status = new VideoStatus();
                        status.setPrivacyStatus("unlisted");
                        videoObjectDefiningMetadata.setStatus(status);

                        // We set a majority of the metadata with the VideoSnippet object.
                        VideoSnippet snippet = new VideoSnippet();


/*
                           * The Calendar instance is used to create a unique name and description for test purposes, so
                           * you can see multiple files being uploaded. You will want to remove this from your project
                           * and use your own standard names.
                           */

                        Calendar cal = Calendar.getInstance();
                        snippet.setTitle(name + " " + cal.getTime());
                        snippet.setDescription(
                                "Video uploaded via YouTube Data API V3 using the Java library " + "on " + cal.getTime());

                        // Set your keywords.
                        List<String> tags = new ArrayList<String>();
                        tags.add("Test");
                        tags.add("Java");
                        tags.add("Auto upload");
                        tags.add("YouTube Data API V3");
                        snippet.setTags(tags);

                        // Set completed snippet to the video object.
                        videoObjectDefiningMetadata.setSnippet(snippet);

                        InputStreamContent mediaContent = new InputStreamContent(
                                VIDEO_FILE_FORMAT, new BufferedInputStream(new FileInputStream(videoFile)));
                        mediaContent.setLength(videoFile.length());


/*
                           * The upload command includes: 1. Information we want returned after file is successfully
                           * uploaded. 2. Metadata we want associated with the uploaded video. 3. Video file itself.
                           */

                        YouTube.Videos.Insert videoInsert = youtube.videos()
                                .insert("snippet,statistics,status", videoObjectDefiningMetadata, mediaContent);

                        // Set the upload type and add event listener.
                        MediaHttpUploader uploader = videoInsert.getMediaHttpUploader();


/*
                           * Sets whether direct media upload is enabled or disabled. True = whole media content is
                           * uploaded in a single request. False (default) = resumable media upload protocol to upload
                           * in data chunks.
                           */

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
                                        break;
                                }
                            }
                        };
                        uploader.setProgressListener(progressListener);

                        // Execute upload.
                        Video returnedVideo = videoInsert.execute();

                        // Print out returned results.
                        System.out.println("\n================== Returned Video ==================\n");
                        System.out.println("  - Id: " + returnedVideo.getId());
                        System.out.println("  - Title: " + returnedVideo.getSnippet().getTitle());
                        System.out.println("  - Tags: " + returnedVideo.getSnippet().getTags());
                        System.out.println("  - Privacy Status: " + returnedVideo.getStatus().getPrivacyStatus());
                        System.out.println("  - Video Count: " + returnedVideo.getStatistics().getViewCount());

                    } catch (GoogleJsonResponseException e) {
                        System.err.println("GoogleJsonResponseException code: " + e.getDetails().getCode() + " : "
                                + e.getDetails().getMessage());
                        e.printStackTrace();
                    } catch (IOException e) {
                        System.err.println("IOException: " + e.getMessage());
                        e.printStackTrace();
                    } catch (Throwable t) {
                        System.err.println("Throwable: " + t.getMessage());
                        t.printStackTrace();
                    }


                } else {
                    System.out.println("Not a video file added!");
                }

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (recursive && (kind == ENTRY_CREATE)) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException x) {
                        // ignore to keep sample readbale
                    }
                }
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }
}

