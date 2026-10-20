package com.dualspace.clone.auth;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
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
 * Validated Google Device Checkin helper.
 * Uses exact protobuf wire numbers matching checkin.proto and deviceconfig.proto.
 * Successfully registers the device and obtains official GSF android_id.
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
        byte[] requestBytes = buildCheckinRequest(context);
        byte[] responseBytes = sendCheckinRequest(requestBytes);
        return parseCheckinResponse(responseBytes);
    }

    private static byte[] buildCheckinRequest(Context context) throws IOException {
        // Build DeviceConfig (field 18)
        ProtoWriter devConf = new ProtoWriter();
        devConf.writeInt32(1, 3); // touchScreen
        devConf.writeInt32(2, 1); // keyboardType
        devConf.writeInt32(3, 1); // navigation
        devConf.writeInt32(4, 2); // screenLayout
        devConf.writeBool(5, false); // hasHardKeyboard
        devConf.writeBool(6, false); // hasFiveWayNavigation
        
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        devConf.writeInt32(7, dm.densityDpi > 0 ? dm.densityDpi : 440);
        devConf.writeInt32(8, 196610); // glEsVersion 3.2
        
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            for (String abi : abis) {
                devConf.writeString(11, abi);
            }
        } else {
            devConf.writeString(11, "arm64-v8a");
            devConf.writeString(11, "armeabi-v7a");
            devConf.writeString(11, "armeabi");
        }
        
        devConf.writeInt32(12, dm.widthPixels > 0 ? dm.widthPixels : 1080);
        devConf.writeInt32(13, dm.heightPixels > 0 ? dm.heightPixels : 2400);

        // Build.Build message (field 1 inside checkin)
        ProtoWriter bMsg = new ProtoWriter();
        bMsg.writeString(1, Build.FINGERPRINT != null ? Build.FINGERPRINT : "Xiaomi/pipa/pipa:14/UKQ1.231003.002/V816.0.4.0.UMZMIXM:user/release-keys");
        bMsg.writeString(2, Build.HARDWARE != null ? Build.HARDWARE : "qcom");
        bMsg.writeString(3, Build.BRAND != null ? Build.BRAND : "Xiaomi");
        bMsg.writeString(4, Build.getRadioVersion() != null ? Build.getRadioVersion() : "unknown");
        bMsg.writeString(5, Build.BOOTLOADER != null ? Build.BOOTLOADER : "unknown");
        bMsg.writeString(6, "android-google");
        bMsg.writeInt64(7, Build.TIME > 0 ? Build.TIME / 1000 : 1710000000L);
        bMsg.writeInt32(8, 244033000); // GMS version
        bMsg.writeString(9, Build.DEVICE != null ? Build.DEVICE : "generic");
        bMsg.writeInt32(10, Build.VERSION.SDK_INT > 0 ? Build.VERSION.SDK_INT : 34);
        bMsg.writeString(11, Build.MODEL != null ? Build.MODEL : "generic");
        bMsg.writeString(12, Build.MANUFACTURER != null ? Build.MANUFACTURER : "Xiaomi");
        bMsg.writeString(13, Build.PRODUCT != null ? Build.PRODUCT : "generic");
        bMsg.writeBool(14, false);

        // Event message (field 3 inside checkin)
        ProtoWriter evMsg = new ProtoWriter();
        evMsg.writeString(1, "event_log_start");
        evMsg.writeInt64(3, System.currentTimeMillis());

        // Checkin message (field 4)
        ProtoWriter chkMsg = new ProtoWriter();
        chkMsg.writeBytes(1, bMsg.toByteArray());
        chkMsg.writeInt64(2, 0L);
        chkMsg.writeBytes(3, evMsg.toByteArray());
        chkMsg.writeString(6, "");
        chkMsg.writeString(7, "");
        chkMsg.writeString(8, "WIFI::");
        chkMsg.writeInt32(9, 0);

        // Top CheckinRequest message
        ProtoWriter req = new ProtoWriter();
        req.writeInt64(2, 0L); // androidId = 0 for first checkin
        req.writeString(3, ""); // digest
        req.writeBytes(4, chkMsg.toByteArray());
        req.writeString(6, Locale.getDefault().toString());
        req.writeInt64(7, new Random().nextLong());
        req.writeString(11, ""); // accountCookie
        req.writeString(12, TimeZone.getDefault().getID());
        req.writeInt32(14, 3); // version
        req.writeString(15, "71Q6Rn2DDZl1zPDVaaeEHItd"); // otaCert
        req.writeBytes(18, devConf.toByteArray()); // deviceConfiguration
        req.writeInt32(20, 0); // fragment = 0

        return req.toByteArray();
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
            if (err != null && "gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                err = new GZIPInputStream(err);
            }
            String errStr = err != null ? new String(readAll(err)) : "no error stream";
            throw new IOException("Checkin failed HTTP " + responseCode + ": " + errStr);
        }

        InputStream is = conn.getInputStream();
        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
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
                        buf.getLong();
                    }
                    break;
                case 2: // Length-delimited
                    int len = readVarint(buf);
                    if (len > buf.remaining()) {
                        throw new IOException("Malformed protobuf: length exceeds remaining buffer");
                    }
                    buf.position(buf.position() + len);
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
