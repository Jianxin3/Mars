package net.minecraft.client.audio;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class WaveData {

    final int format;
    final int samplerate;
    final int bytesPerFrame;
    private final AudioInputStream audioStream;
    private final ByteArrayOutputStream byteArrayOutputStream;
    ByteBuffer data;

    public WaveData(AudioInputStream stream) {
        this.audioStream = stream;
        AudioFormat audioFormat = stream.getFormat();
        format = getOpenAlFormat(audioFormat.getChannels(), audioFormat.getSampleSizeInBits());
        this.samplerate = (int) audioFormat.getSampleRate();
        this.bytesPerFrame = audioFormat.getFrameSize();
        byteArrayOutputStream = new ByteArrayOutputStream();
        loadData();
    }

    public static WaveData create(String file) {
        InputStream stream = Class.class.getResourceAsStream("/" + file);
        if (stream == null) {
            System.err.println("Couldn't find file: " + file);
            return null;
        }
        InputStream bufferedInput = new BufferedInputStream(stream);
        AudioInputStream audioStream = null;
        try {
            audioStream = AudioSystem.getAudioInputStream(bufferedInput);
        } catch (UnsupportedAudioFileException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        WaveData wavStream = new WaveData(audioStream);
        return wavStream;
    }

    private static int getOpenAlFormat(int channels, int bitsPerSample) {
        if (channels == 1) {
            return bitsPerSample == 8 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16;
        } else {
            return bitsPerSample == 8 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_STEREO16;
        }
    }

    protected void dispose() {
        try {
            audioStream.close();
            data.clear();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ByteBuffer loadData() {
        try {
            int bytesRead = IOUtils.copy(audioStream, byteArrayOutputStream);
            data = BufferUtils.createByteBuffer(bytesRead);
            data.clear();
            data.put(byteArrayOutputStream.toByteArray(), 0, bytesRead);
            data.flip();
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Couldn't read bytes from audio stream!");
        }
        return data;
    }

}
