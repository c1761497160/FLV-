package com.woxu.flvaudio;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.dcloud.feature.uniapp.common.UniModule;

public class WoxuFlvAudioModule extends UniModule {
    private static final String TAG = "WoxuFlvAudio";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void startDecode(String flvUrl, int slotIndex, final UniJSCallback callback) {
        if (running.get()) return;
        running.set(true);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            MediaCodec decoder = null;

            try {
                Log.i(TAG, "[槽位" + slotIndex + "] 连接: " + flvUrl);
                URL url = new URL(flvUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(0);
                is = conn.getInputStream();

                byte[] buf = new byte[8192];
                byte[] acc = new byte[0];
                int parseOff = 0;
                byte[] audioCfg = null;
                boolean codecReady = false;
                DecodeBuffer db = new DecodeBuffer(slotIndex);
                int totalTags = 0;

                while (running.get()) {
                    int read = is.read(buf);
                    if (read == -1) break;
                    acc = Arrays.copyOf(acc, acc.length + read);
                    System.arraycopy(buf, 0, acc, acc.length - read, read);

                    while (true) {
                        if (parseOff == 0) {
                            if (acc.length < 13) break;
                            parseOff = 13;
                        }
                        if (parseOff + 15 > acc.length) break;
                        int tt = acc[parseOff] & 0xFF;
                        int ds = ((acc[parseOff+1]&0xFF)<<16) | ((acc[parseOff+2]&0xFF)<<8) | (acc[parseOff+3]&0xFF);
                        int tl = 11 + ds + 4;
                        if (parseOff + tl > acc.length) break;
                        totalTags++;

                        if (tt == 8) {
                            int sf = (acc[parseOff+11] & 0xFF) >> 4;
                            if (sf == 10) { // AAC
                                int aacType = acc[parseOff+12] & 0xFF;
                                int aacOff = parseOff + 13;
                                int aacLen = ds - 2;
                                if (aacType == 0 && aacLen > 0) {
                                    audioCfg = Arrays.copyOfRange(acc, aacOff, aacOff + aacLen);
                                    Log.i(TAG, "[槽位" + slotIndex + "] AAC配置");
                                } else if (aacType == 1 && aacLen > 0 && audioCfg != null) {
                                    byte[] raw = Arrays.copyOfRange(acc, aacOff, aacOff + aacLen);
                                    byte[] adts = buildAdts(raw.length, audioCfg);
                                    byte[] pkt = new byte[adts.length + raw.length];
                                    System.arraycopy(adts, 0, pkt, 0, adts.length);
                                    System.arraycopy(raw, 0, pkt, adts.length, raw.length);
                                    if (!codecReady) {
                                        decoder = createDecoder(audioCfg);
                                        codecReady = true;
                                    }
                                    if (decoder != null) decodeFrame(decoder, pkt, db);
                                }
                            } else if (sf == 7 || sf == 8) { // G.711A / G.711U
                                int rawOff = parseOff + 12;
                                int rawLen = ds - 1;
                                byte[] rawPcm = new byte[rawLen * 2];
                                for (int i = 0; i < rawLen; i++) {
                                    int val = acc[rawOff + i] & 0xFF;
                                    short pcm = (short)(sf == 7 ? alawToPcm(val) : ulawToPcm(val));
                                    rawPcm[i*2] = (byte)(pcm & 0xFF);
                                    rawPcm[i*2+1] = (byte)((pcm >> 8) & 0xFF);
                                }
                                db.add(rawPcm);
                            }
                        }
                        parseOff += tl;
                    }
                    if (parseOff > 0) {
                        acc = Arrays.copyOfRange(acc, parseOff, acc.length);
                        parseOff = 0;
                    }
                }
                Log.i(TAG, "[槽位" + slotIndex + "] 结束, 总标签=" + totalTags);
            } catch (Exception e) {
                Log.e(TAG, "[槽位" + slotIndex + "] 错误: " + e.getMessage());
            } finally {
                if (decoder != null) try { decoder.stop(); decoder.release(); } catch(Exception e) {}
                if (is != null) try { is.close(); } catch(Exception e) {}
                if (conn != null) conn.disconnect();
                running.set(false);
                Log.i(TAG, "[槽位" + slotIndex + "] 停止");
            }
        });
    }

    public void stopDecode() { running.set(false); }

    private byte[] buildAdts(int len, byte[] cfg) {
        int p = ((cfg[0]&0xF8)>>3)-1, s = ((cfg[0]&0x07)<<1)|((cfg[1]&0x80)>>7), c = (cfg[1]&0x78)>>3;
        int fl = 7+len; byte[] a = new byte[7];
        a[0]=(byte)0xFF; a[1]=(byte)0xF1;
        a[2]=(byte)(((p<<6)&0xC0)|((s<<2)&0x3C)|((c>>2)&0x01));
        a[3]=(byte)(((c<<6)&0xC0)|((fl>>11)&0x03));
        a[4]=(byte)((fl>>3)&0xFF);
        a[5]=(byte)(((fl<<5)&0xE0)|0x1F);
        a[6]=(byte)0xFC;
        return a;
    }

    private MediaCodec createDecoder(byte[] cfg) throws Exception {
        int s = ((cfg[0]&0x07)<<1)|((cfg[1]&0x80)>>7), c = (cfg[1]&0x78)>>3;
        int sr = getSr(s); if (sr<=0) sr=8000; if (c<=0) c=1;
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, c);
        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1);
        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, 0);
        codec.start();
        return codec;
    }

    private void decodeFrame(MediaCodec codec, byte[] frame, DecodeBuffer db) {
        try {
            int inIdx = codec.dequeueInputBuffer(10000);
            if (inIdx < 0) return;
            ByteBuffer inBuf = codec.getInputBuffer(inIdx);
            if (inBuf == null) return;
            inBuf.clear(); inBuf.put(frame);
            codec.queueInputBuffer(inIdx, 0, frame.length, System.nanoTime()/1000, 0);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    byte[] pcm = new byte[info.size];
                    outBuf.get(pcm);
                    codec.releaseOutputBuffer(outIdx, false);
                    db.add(pcm);
                } else {
                    codec.releaseOutputBuffer(outIdx, false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解码帧失败: " + e.getMessage());
        }
    }

    private int alawToPcm(int val) {
        int sign = (val & 0x80) << 1; val = val & 0x7F;
        if (val < 16) return sign | (val << 4) | 8;
        int exp = ((val >> 4) & 0x07) - 1;
        int mant = val & 0x0F;
        return sign | (((mant << 4) | 0x80) << exp);
    }

    private int ulawToPcm(int val) {
        int BIAS = 0x84; val = ~val;
        int sign = (val & 0x80) << 1; val = val & 0x7F;
        int exp = (val >> 4) & 0x07; int mant = val & 0x0F;
        int pcm = (mant << 4) | 0x80;
        if (exp > 0) pcm = (pcm | 0x100) << (exp - 1);
        return sign | (pcm - BIAS);
    }

    private class DecodeBuffer {
        static final int TARGET = 8192;
        byte[] buf = new byte[0];
        int slotIdx;
        DecodeBuffer(int si) { slotIdx = si; }
        synchronized void add(byte[] data) {
            buf = Arrays.copyOf(buf, buf.length + data.length);
            System.arraycopy(data, 0, buf, buf.length - data.length, data.length);
            if (buf.length >= TARGET) {
                String b64 = Base64.encodeToString(buf, Base64.NO_WRAP);
                sendPcm(b64, slotIdx);
                buf = new byte[0];
            }
        }
    }

    private void sendPcm(String base64, int si) {
        if (mWXSDKInstance == null) return;
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "pcm"); map.put("slotIndex", si); map.put("data", base64);
            mWXSDKInstance.fireGlobalEventCallback("flvAudioData", map);
        } catch (Exception e) { Log.e(TAG, "发送失败: " + e.getMessage()); }
    }

    private int getSr(int idx) {
        int[] r = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350,0,0,0};
        return (idx>=0 && idx<r.length) ? r[idx] : 0;
    }
}
