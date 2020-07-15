package com.clevertap.stormdb;

import static com.clevertap.stormdb.StormDB.RECORDS_PER_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import com.clevertap.stormdb.exceptions.StormDBException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// TODO: 10/07/2020 test with more than Integer.MAX_VALUE records
class StormDBTest {

    @Test
    void simpleTest() throws IOException, StormDBException {
        final Path path = Files.createTempDirectory("stormdb");

        final int valueSize = 28;
        final StormDB db = new StormDB(valueSize, path.toString(), false);

        final int records = 100;
        for (int i = 0; i < records; i++) {
            final ByteBuffer value = ByteBuffer.allocate(valueSize);
            value.putInt((int) (Math.random() * 100000000)); // Insert a random value.
            db.put(i, value.array());

            value.clear();
            value.putInt(i); // Insert a predictable value.
            db.put(i, value.array());
        }

        // Verify.
        for (int i = 0; i < records; i++) {
            final byte[] bytes = db.randomGet(i);
            final ByteBuffer value = ByteBuffer.wrap(bytes);
            assertEquals(i, value.getInt());
        }

        // Iterate sequentially.
        db.iterate(new EntryConsumer() {
            @Override
            public void accept(int key, byte[] data, int offset) {
                final ByteBuffer value = ByteBuffer.wrap(data, offset, valueSize);
                assertEquals(key, value.getInt());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1,
            RECORDS_PER_BLOCK - 1,
            RECORDS_PER_BLOCK,
            RECORDS_PER_BLOCK + 1,
            100, 1000, 10_000, 100_000, 200_000, 349_440})
    void compactionTest(final int totalRecords) throws IOException, StormDBException {
        final Path path = Files.createTempDirectory("stormdb");

        final int valueSize = 8;
        final StormDB db = new StormDB(valueSize, path.toString(), false);

        final HashMap<Integer, Long> kvCache = new HashMap<>();

//        final int totalRecords = 100;
        for (int i = 0; i < totalRecords; i++) {
            long val = (long) (Math.random() * Long.MAX_VALUE);
            final ByteBuffer value = ByteBuffer.allocate(valueSize);
            value.putLong(val); // Insert a random value.
            db.put(i, value.array());
            kvCache.put(i, val);
        }

        // Make sure all is well
        verifyDb(db, totalRecords, kvCache);

        // Now compact
        db.compact();

        // Make sure all is well
        verifyDb(db, totalRecords, kvCache);

        int count = totalRecords / 2;
        while (count-- > 0) {
            final ByteBuffer value = ByteBuffer.allocate(valueSize);
            long val = (long) (Math.random() * Long.MAX_VALUE);
            value.putLong(val); // Insert a random value.
            final int randomKey = (int) (Math.random() * totalRecords);
            db.put(randomKey, value.array());
            kvCache.put(randomKey, val);
        }

        // Make sure all is well
        verifyDb(db, totalRecords, kvCache);

        db.compact();

        // Make sure all is well
        verifyDb(db, totalRecords, kvCache);
    }

    private void verifyDb(StormDB db, int records, HashMap<Integer, Long> kvCache)
            throws IOException, StormDBException {
        // Verify.
        for (int i = 0; i < records; i++) {
            final byte[] bytes = db.randomGet(i);
            final ByteBuffer value = ByteBuffer.wrap(bytes);
            assertEquals(kvCache.get(i), value.getLong());
        }
    }

    @Test
    void testMultiThreaded() throws IOException, InterruptedException, StormDBException {
        final Path path = Files.createTempDirectory("stormdb");

        final int valueSize = 8;
        final StormDB db = new StormDB(valueSize, path.toString(), false);

        final int totalRecords = 1_000_000;
        final int maxSleepMs = 100;
        final int[] timeToRunInSeconds = {10};
        long[] kvCache = new long[totalRecords];

        final Boolean[] exceptionThrown = { false };
        final Boolean[] shutdown = { false };
        final ExecutorService service = Executors.newFixedThreadPool(4);

        // Writer thread.
        service.submit(() -> {
            while(!shutdown[0]) {
                int iterationNumber = 1;
                for (int i = 0; i < totalRecords; i++) {
                    long val = (i % 1000) * iterationNumber;
                    final ByteBuffer value = ByteBuffer.allocate(valueSize);
                    value.putLong(val); // Insert a random value.
                    synchronized (kvCache) {
                        kvCache[i] = val; // Update cache first.
                    }
                    try {
                        db.put(i, value.array());
                    } catch (IOException e) {
                        e.printStackTrace();
                        exceptionThrown[0] = true;
                    }
                }
                sleepRandomMs("writer", maxSleepMs);
                iterationNumber++;
            }
            System.out.println("Finished writer thread.");
        });

        // Compaction thread
        service.submit(() -> {
            while(!shutdown[0]) {
                try {
                    db.compact();
                } catch (IOException e) {
                    e.printStackTrace();
                    exceptionThrown[0] = true;
                }
                sleepRandomMs("compaction", maxSleepMs);
            }
            System.out.println("Finished compaction thread.");
        });

        service.submit(() -> {
            while (!shutdown[0]) {
                // Iterate sequentially.
                try {
                    final int[] prevKey = {Integer.MIN_VALUE};
                    db.iterate((key, data, offset) -> {
                        assertTrue(prevKey[0] != key);
                        prevKey[0] = key;
                        final ByteBuffer value = ByteBuffer.wrap(data, offset, valueSize);
                        synchronized (kvCache) {
                            assertTrue(kvCache[key] >= value.getLong());
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    exceptionThrown[0] = true;
                }
//                sleepRandomMs("iterate", maxSleepMs);
            }
            System.out.println("Finished iteration thread.");
        });

        // Tracker thread
        service.submit(() -> {
            while(timeToRunInSeconds[0]-- > 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            shutdown[0] = true;
            System.out.println("Finished tracker thread. Exiting.");
        });

        // Verifier / Reader

        while (!shutdown[0]) {
//                    System.out.println("VERIFY1=");
            for (int i = 0; i < totalRecords; i++) {
                final byte[] bytes;
                try {
//                            System.out.println("VERIFY2-" + i);
                    bytes = db.randomGet(i);
                    if (bytes == null) {
                        continue;
                    }
                    final ByteBuffer value = ByteBuffer.wrap(bytes);
                    final long longValue = value.getLong();
//                            System.out.println("VERIFY3-" + i + " value=" + longValue +
//                                    " kvCache[i] = " + kvCache[i]);
                    synchronized (kvCache) {
                        if(kvCache[i] < longValue) {
                            System.out.println(db.dbgList.get(db.dbgList.size()-3));
                            System.out.println(db.dbgList.get(db.dbgList.size()-2));
                            System.out.println(db.dbgList.get(db.dbgList.size()-1));
                            System.out.println("Break found. - " + i + " / "+ kvCache[i] + " / "+ longValue);
                        }
                        assertTrue(kvCache[i] >= longValue);
                    }
//                            System.out.println("VERIFY4-" + i);
                } catch (IOException e) {
                    exceptionThrown[0] = true;
                    throw e;
                }
            }
            sleepRandomMs("verifier", maxSleepMs);
        }
        System.out.println("Completed verification in main thread.");

        System.out.println("service.awaitTermination for 5 seconds started.");
        service.shutdown();
        assertTrue(service.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(exceptionThrown[0]);
    }

    private void sleepRandomMs(String caller, int maxMs) {
        try {
            long sleepTime = (long)(maxMs * Math.random());
            System.out.println("Sleeping "+caller+" for "+sleepTime+" ms.");
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testMidWayFileDelete() throws IOException {
        // This tests java bug highlighted below. Can remove later.
        // https://stackoverflow.com/questions/991489/file-delete-returns-false-even-though-file-exists-file-canread-file-canw
        final int totalLines = 1000000;
        final Path path = Files.createTempDirectory("testdelete");
        final String tempFileName = path.toString() + "/temp.txt";
        try {
            FileWriter myWriter = new FileWriter(tempFileName);
            for (int i = 0; i < totalLines; i++) {
                myWriter.write("This is a test line.\n");
            }
            myWriter.close();
            System.out.println("Successfully wrote to the file.");

            File file = new File(tempFileName);    //creates a new file instance
            FileReader fr = new FileReader(file);   //reads the file
            BufferedReader br = new BufferedReader(fr,
                    128);  //creates a buffering character input stream
            int c = 0;
            while ((br.readLine()) != null) {
                if (c++ == totalLines / 2) {
                    file.delete();
                    System.out.println(c);
                }
            }
            fr.close();    //closes the stream and release the resources
        } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }
    }
}