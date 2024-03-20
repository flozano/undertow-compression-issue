package io.github.flozano.undertowcompressionissue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class MirrorServlet extends HttpServlet {


	private static final Logger LOGGER = LoggerFactory.getLogger(MirrorServlet.class);
	private final int bufferSize;

	public MirrorServlet(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		LOGGER.info("Received request: {}", req.getRequestURI());
		resp.setContentType(req.getContentType());
		resp.setContentLength(req.getContentLength());
		resp.setCharacterEncoding(req.getCharacterEncoding());
		LOGGER.info("Copying input to output (bufferSize={})", bufferSize);
		resp.setStatus(200);
		try (var inputStream = req.getInputStream()) {
			try (var outputStream = resp.getOutputStream()) {
				if (bufferSize == 0) {
					var content = req.getInputStream().readAllBytes();
					resp.getOutputStream().write(content);
				} else {
					byte[] buffer = new byte[bufferSize];
					copyLarge(inputStream, outputStream, buffer);
				}
			}
		}
		LOGGER.info("Finished request: {}", req.getRequestURI());
	}

	public static long copyLarge(final InputStream inputStream, final OutputStream outputStream, final byte[] buffer)
			throws IOException {
		// copied from commons-io, just added log statements
		Objects.requireNonNull(inputStream, "inputStream");
		Objects.requireNonNull(outputStream, "outputStream");
		long count = 0;
		int n;
		while (EOF != (n = inputStream.read(buffer))) {
			LOGGER.info("Read {} bytes", n);
			outputStream.write(buffer, 0, n);
			count += n;
			LOGGER.info("Copied {} bytes (count={})", n, count);
		}
		return count;
	}

	public static final int EOF = -1;

}
