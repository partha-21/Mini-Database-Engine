package minidb.transaction;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public class WALManager {
    private final String path;
    private RandomAccessFile file;
    private long syncedLength;
    private boolean tornTail;
    private boolean corruptMiddle;

    public WALManager(String path) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path, "rw");
        this.file.seek(file.length());
        this.syncedLength = file.length();
    }

    public synchronized void append(LogRecord record) throws IOException {
        byte[] bytes = record.toBytes();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ByteBuffer frame = ByteBuffer.allocate(8 + bytes.length);
        frame.putInt(bytes.length);
        frame.putInt((int) crc.getValue());
        frame.put(bytes);
        file.write(frame.array());
    }

    public synchronized void fsync() throws IOException {
        file.getFD().sync();
        syncedLength = file.length();
    }

    public synchronized long size() throws IOException {
        return file.length();
    }

    public synchronized List<LogRecord> readAll() throws IOException {
        List<LogRecord> records = new ArrayList<>();
        tornTail = false;
        corruptMiddle = false;
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            long length = r.length();
            while (r.getFilePointer() < length) {
                if (length - r.getFilePointer() < 8) {
                    tornTail = true;
                    break;
                }
                int len = r.readInt();
                int crcStored = r.readInt();
                if (len <= 0 || len > (16 << 20)) {
                    if (length - r.getFilePointer() > 0) corruptMiddle = true;
                    else tornTail = true;
                    break;
                }
                if (len > length - r.getFilePointer()) {
                    tornTail = true;
                    break;
                }
                byte[] data = new byte[len];
                r.readFully(data);
                CRC32 crc = new CRC32();
                crc.update(data);
                if ((int) crc.getValue() != crcStored) {
                    if (r.getFilePointer() < length) {
                        corruptMiddle = true;
                    } else {
                        tornTail = true;
                    }
                    break;
                }
                records.add(LogRecord.fromBytes(data));
            }
        }
        return records;
    }

    public synchronized boolean wasTornTail() {
        return tornTail;
    }

    public synchronized boolean hadCorruptMiddle() {
        return corruptMiddle;
    }

    public synchronized void truncate() throws IOException {
        file.setLength(0);
        file.seek(0);
        file.getFD().sync();
        syncedLength = 0;
    }

    public synchronized void close() throws IOException {
        file.close();
    }

    public synchronized void simulateCrash(boolean loseUnsyncedTail) throws IOException {
        if (loseUnsyncedTail) file.setLength(syncedLength);
        file.close();
    }
}
