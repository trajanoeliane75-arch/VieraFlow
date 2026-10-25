package br.dev.sebasty.vieraflow;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Change container without decoding or encoding. Android supplies the codecs' sample descriptions. */
final class Mp4Remuxer {
    static void convert(File input, File output) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null; boolean started = false, success = false;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> tracks = new HashMap<>(); boolean video = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if ("video/avc".equals(mime)) video = true;
                else if (!"audio/mp4a-latm".equals(mime))
                    throw new IOException("Este vídeo precisa de recodificação (" + mime + "). Escolha uma fonte H.264/AAC.");
                tracks.put(i, muxer.addTrack(format)); extractor.selectTrack(i);
            }
            if (!video) throw new IOException("A fonte não contém vídeo H.264 compatível.");
            muxer.start(); started = true;
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long base = -1; int count = 0;
            while (extractor.getSampleTrackIndex() >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Preparação cancelada.");
                if (output.getParentFile().getUsableSpace() < 32L * 1024 * 1024) throw new IOException("Sem espaço para preparar o vídeo. Libere armazenamento no celular.");
                buffer.clear(); int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long pts = extractor.getSampleTime(); if (base < 0) base = pts;
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, Math.max(0, pts - base), flags);
                muxer.writeSampleData(tracks.get(extractor.getSampleTrackIndex()), buffer, info);
                count++; extractor.advance();
            }
            if (count == 0) throw new IOException("O vídeo não contém amostras reproduzíveis.");
            muxer.stop(); started = false; success = true;
        } catch (RuntimeException e) { throw new IOException("Não consegui preparar MP4 desta fonte: " + e.getMessage(), e); }
        finally {
            extractor.release();
            if (muxer != null) { if (started) try { muxer.stop(); } catch (Exception ignored) { } muxer.release(); }
            if (!success) output.delete();
        }
    }
}
