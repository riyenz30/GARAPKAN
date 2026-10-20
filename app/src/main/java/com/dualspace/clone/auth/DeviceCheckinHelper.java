package com.dualspace.clone.auth;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal standalone Google Device Checkin helper.
 * Encodes an AndroidCheckinRequest protobuf without protobuf-java dependencies,
 * sends it to https://android.clients.google.com/checkin, and extracts the
 * assigned android_id (GSF ID) and security_token.
 */
public class DeviceCheckinHelper {
    private static final String TAG = "DeviceCheckin";
    private static final String CHECKIN_URL = "https://android.clients.google.com/checkin";

    public static class CheckinResult {
        public final long androidId;
        public final long securityToken;

        public CheckinResult(long androidId, long securityToken) {
            this.androidId = androidId;
            this.securityToken = securityToken;
        }

        public String getAndroidIdHex() {
            return Long.toHexString(androidId);
        }
    }

    public static CheckinResult checkin(Context context) throws IOException {
        byte[] requestBytes = buildCheckinRequest();
        byte[] responseBytes = sendCheckinRequest(requestBytes);
        return parseCheckinResponse(responseBytes);
    }

    private static byte[] buildCheckinRequest() throws IOException {
        ProtoWriter top = new ProtoWriter();

        // field 4: checkin (message)
        ProtoWriter checkin = new ProtoWriter();

        // checkin -> field 1: build (message)
        ProtoWriter buildMsg = new ProtoWriter();
        buildMsg.writeString(1, Build.FINGERPRINT != null ? Build.FINGERPRINT : "unknown");
        buildMsg.writeString(2, Build.HARDWARE != null ? Build.HARDWARE : "unknown");
        buildMsg.writeString(3, Build.BRAND != null ? Build.BRAND : "generic");
        buildMsg.writeString(4, Build.RADIO != null ? Build.RADIO : "unknown");
        buildMsg.writeString(5, Build.BOOTLOADER != null ? Build.BOOTLOADER : "unknown");
        buildMsg.writeString(6, "android-google");
        buildMsg.writeInt64(7, Build.TIME / 1000);
        buildMsg.writeInt32(8, 233013000); // GMS version code
        buildMsg.writeString(9, Build.DEVICE != null ? Build.DEVICE : "generic");
        buildMsg.writeInt32(10, Build.VERSION.SDK_INT);
        buildMsg.writeString(11, Build.MODEL != null ? Build.MODEL : "generic");
        buildMsg.writeString(12, Build.MANUFACTURER != null ? Build.MANUFACTURER : "unknown");
        buildMsg.writeString(13, Build.PRODUCT != null ? Build.PRODUCT : "generic");
        buildMsg.writeBool(14, false); // otaInstalled

        checkin.writeBytes(1, buildMsg.toByteArray());
        checkin.writeString(6, ""); // cellOperator
        checkin.writeString(7, ""); // simOperator
        checkin.writeString(8, ""); // roaming
        checkin.writeInt32(9, 0);   // userNumber

        top.writeBytes(4, checkin.toByteArray());

        // field 6: locale (string)
        Locale locale = Locale.getDefault();
        top.writeString(6, locale.toString());

        // field 7: loggingId (int64)
        top.writeInt64(7, new Random().nextLong());

        // field 14: version (int32) = 3
        top.writeInt32(14, 3);

        // field 17: timeZone (string)
        top.writeString(17, TimeZone.getDefault().getID());

        // field 18: otaCert (repeated string)
        top.writeString(18, "71Q6Rn2DDZl1zPDVaaeEHItd");

        return top.toByteArray();
    }

    private static byte[] sendCheckinRequest(byte[] protobufBytes) throws IOException {
        URL url = new URL(CHECKIN_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/x-protobuffer");
        conn.setRequestProperty("Content-Encoding", "gzip");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("User-Agent", "Android-Checkin/2.0 (" + Build.DEVICE + " " + Build.ID + "); gzip");

        // GZIP compress request body
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(protobufBytes);
        gzos.close();

        OutputStream os = conn.getOutputStream();
        os.write(baos.toByteArray());
        os.flush();
        os.close();

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            InputStream err = conn.getErrorStream();
            String errStr = err != null ? new String(readAll(err)) : "no error stream";
            throw new IOException("Checkin failed HTTP " + responseCode + ": " + errStr);
        }

        InputStream is = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding)) {
            is = new GZIPInputStream(is);
        }
        return readAll(is);
    }

    private static CheckinResult parseCheckinResponse(byte[] data) throws IOException {
        long androidId = 0;
        long securityToken = 0;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        while (buf.hasRemaining()) {
            int tag = readVarint(buf);
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x07;

            switch (wireType) {
                case 0: // Varint
                    readVarint64(buf);
                    break;
                case 1: // 64-bit
                    if (fieldNum == 7) {
                        androidId = buf.getLong();
                    } else if (fieldNum == 8) {
                        securityToken = buf.getLong();
                    } else {
                        buf.getLong(); // skip
                    }
                    break;
                case 2: // Length-delimited
                    int len = readVarint(buf);
                    if (len > buf.remaining()) {
                        throw new IOException("Malformed protobuf: length exceeds remaining buffer");
                    }
                    buf.position(buf.position() + len); // skip
                    break;
                case 5: // 32-bit
                    buf.getInt();
                    break;
                default:
                    throw new IOException("Unknown protobuf wire type: " + wireType);
            }
        }

        if (androidId == 0) {
            throw new IOException("Checkin response did not contain a valid android_id");
        }

        return new CheckinResult(androidId, securityToken);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        return baos.toByteArray();
    }

    private static int readVarint(ByteBuffer buf) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 32) {
            if (!buf.hasRemaining()) throw new IOException("Unexpected EOF reading varint");
            byte b = buf.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Malformed varint");
    }

    private static long readVarint64(ByteBuffer buf) throws IOException {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            if (!buf.hasRemaining()) throw new IOException("Unexpected EOF reading varint64");
            byte b = buf.get();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IOException("Malformed varint64");
    }

    /**
     * Minimal Protobuf binary encoder helper.
     */
    private static class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public void writeTag(int fieldNumber, int wireType) {
            writeVarint((fieldNumber << 3) | wireType);
        }

        public void writeVarint(int val) {
            while ((val & ~0x7F) != 0) {
                out.write((val & 0x7F) | 0x80);
                val >>>= 7;
            }
            out.write(val & 0x7F);
        }

        public void writeVarint64(long val) {
            while ((val & ~0x7FL) != 0) {
                out.write((int) ((val & 0x7FL) | 0x80L));
                val >>>= 7;
            }
            out.write((int) (val & 0x7FL));
        }

        public void writeInt32(int fieldNumber, int val) {
            writeTag(fieldNumber, 0);
            writeVarint(val);
        }

        public void writeInt64(int fieldNumber, long val) {
            writeTag(fieldNumber, 0);
            writeVarint64(val);
        }

        public void writeBool(int fieldNumber, boolean val) {
            writeTag(fieldNumber, 0);
            out.write(val ? 1 : 0);
        }

        public void writeString(int fieldNumber, String val) {
            if (val == null) return;
            try {
                byte[] bytes = val.getBytes("UTF-8");
                writeTag(fieldNumber, 2);
                writeVarint(bytes.length);
                out.write(bytes);
            } catch (Exception ignored) {}
        }

        public void writeBytes(int fieldNumber, byte[] bytes) {
            if (bytes == null) return;
            writeTag(fieldNumber, 2);
            writeVarint(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
