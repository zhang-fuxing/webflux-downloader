package cn.com.kingshine.kingshine.download.server.util;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * @author 张福兴
 * @version 1.0
 * @date 2025/6/6
 * @email zhangfuxing1010@163.com
 */
public class DownloadServer {
	private int bufferSize = 4096;

	private TokenBucket tokenBucket;

	public DownloadServer() {
	}

	public DownloadServer(int bufferSize, TokenBucket tokenBucket) {
		this.bufferSize = bufferSize;
		this.tokenBucket = tokenBucket;
	}

	public DownloadServer(long capacity, long refillRatePerSecond) {
		this(capacity, refillRatePerSecond, 4096);
	}

	public DownloadServer(long capacity, long refillRatePerSecond, int bufferSize) {
		this.bufferSize = bufferSize;
		if (capacity > 0 && refillRatePerSecond > 0) {
			this.tokenBucket = new TokenBucket(capacity, refillRatePerSecond);
		}
	}

	public Mono<ResponseEntity<Flux<DataBuffer>>> download(String filepath, String range) {
		return download(filepath, new DefaultDataBufferFactory(), range);
	}

	public Mono<ResponseEntity<Flux<DataBuffer>>> download(String filepath) {
		return download(filepath, new DefaultDataBufferFactory(), null);
	}

	public Mono<ResponseEntity<Flux<DataBuffer>>> download(String filepath, DataBufferFactory bufferFactory) {
		return download(filepath, bufferFactory, null, null);
	}

	public Mono<ResponseEntity<Flux<DataBuffer>>> download(String filepath, DataBufferFactory bufferFactory, String range) {
		return download(filepath, bufferFactory, range, null);
	}

	public Mono<ResponseEntity<Flux<DataBuffer>>> download(String filepath, DataBufferFactory bufferFactory, String range, String userAgent) {
		if ("electron/kingshine".equalsIgnoreCase(userAgent) && !StringUtils.hasLength(range)) {
			File file = new File(filepath);
			ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity.status(HttpStatus.OK)
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.contentLength(file.length())
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s\""
							.formatted(URLEncoder.encode(new File(filepath).getName(), StandardCharsets.UTF_8)));
			return Mono.just(bodyBuilder.build());
		}
		return Mono.fromCallable(() -> {
					// 解析 Range 请求头
					Path path = Paths.get(filepath);
					long fileSize = Files.size(path);
					long start = 0;
					long end = fileSize - 1;
					if (range != null && range.startsWith("bytes=")) {
						String[] parts = range.substring(6).split("-");
						start = Long.parseLong(parts[0]);
						if (parts.length > 1 && !parts[1].isEmpty()) {
							end = Long.parseLong(parts[1]);
						}
					}

					if (start < 0 || end >= fileSize || start > end) {
						start = 0;
						end = fileSize - 1;
					}

					long contentLength = end - start + 1;
					return new ResourceInfo(
							fileSize,
							start,
							end,
							contentLength,
							Files.newByteChannel(path, StandardOpenOption.READ));
				})
				.subscribeOn(Schedulers.boundedElastic())
				.flatMap(entry -> {

					Flux<DataBuffer> bufferFlux = DataBufferUtils.readByteChannel(entry::channel, bufferFactory, this.bufferSize)
							.flatMap(dataBuffer -> tokenBucket == null ? Mono.just(dataBuffer) :
											this.tokenBucket.consume(dataBuffer.readableByteCount()).thenReturn(dataBuffer),
									1)
							.doOnTerminate(() -> Schedulers.boundedElastic()
									.schedule(() -> {
										try {
											ReadableByteChannel channel = entry.channel;
											if (channel != null && channel.isOpen()) {
												channel.close();
											}
										} catch (IOException e) {
											throw new RuntimeException("关闭通道失败", e);
										}
									}));
					HttpStatus status = range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
					ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity.status(status)
							.contentType(MediaType.APPLICATION_OCTET_STREAM)
							.contentLength(entry.contentLength)
							.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s\""
									.formatted(URLEncoder.encode(new File(filepath).getName(), StandardCharsets.UTF_8)));
					// 添加 Content-Range 头
					if (range != null) {
						bodyBuilder.header(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", entry.start(), entry.end(), entry.fileSize()));
					}
					ResponseEntity<Flux<DataBuffer>> responseEntity = bodyBuilder.body(bufferFlux);
					return Mono.just(responseEntity);
				});
	}

	private record ResourceInfo(long fileSize, long start, long end, long contentLength, ReadableByteChannel channel) {
	}
}
