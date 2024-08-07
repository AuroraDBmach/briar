package org.briarproject.bramble.transport;

import org.briarproject.bramble.api.crypto.StreamEncrypter;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.bramble.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;

/**
 * An {@link OutputStream} that packs data into transport frames, writing a
 * frame whenever there is a full frame to write or the {@link #flush()} method
 * is called.
 */
@NotThreadSafe
@NotNullByDefault
class StreamWriterImpl extends OutputStream implements StreamWriter {

	private final StreamEncrypter encrypter;
	private final byte[] payload;
	/// length Stores how long the content that is currently in the buffer "payload" is.
	private int length = 0;

	StreamWriterImpl(StreamEncrypter encrypter) {
		this.encrypter = encrypter;
		payload = new byte[MAX_PAYLOAD_LENGTH];
	}

	@Override
	public OutputStream getOutputStream() {
		return this;
	}

	@Override
	public void sendEndOfStream() throws IOException {
		writeFrame(true);
		encrypter.flush();
	}

	@Override
	public void close() throws IOException {
		writeFrame(true);
		encrypter.flush();
		super.close();
	}

	@Override
	public void flush() throws IOException {
		writeFrame(false);
		encrypter.flush();
	}

	@Override
	public void write(int b) throws IOException {
		payload[length] = (byte) b;
		length++;
		if (length == payload.length) writeFrame(false);
	}

	@Override
	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		//Frame Segmentation
		// available = How much free space ther is at the end of the buffer "payload"
		int available = payload.length - length;
		while (available <= len) {
			System.arraycopy(b, off, payload, length, available);
			length += available;
			writeFrame(false);
			off += available;
			len -= available;
			available = payload.length - length;
		}
		System.arraycopy(b, off, payload, length, len);
		length += len;
	}

	private void writeFrame(boolean finalFrame) throws IOException {
		encrypter.writeFrame(payload, length, 0, finalFrame);
		length = 0;
	}
}
