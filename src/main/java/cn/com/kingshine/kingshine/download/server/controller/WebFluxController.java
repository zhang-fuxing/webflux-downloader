package cn.com.kingshine.kingshine.download.server.controller;

import cn.com.kingshine.kingshine.download.server.util.DownloadServer;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author 张福兴
 * @version 1.0
 * @date 2025/6/5
 * @email zhangfuxing1010@163.com
 */
@RestController
@CrossOrigin
@RequestMapping("/api/webflux")
public class WebFluxController {
	private static final String FILE_PATH = "D:/ISO/win10.iso";
	final DataBufferFactory dataBufferFactory;

	public WebFluxController(DataBufferFactory dataBufferFactory) {
		this.dataBufferFactory = dataBufferFactory;
	}

	@GetMapping("/download")
	public Mono<ResponseEntity<Flux<DataBuffer>>> downloadFile(
			@RequestHeader(value = "Range", required = false) String rangeHeader) {
		// 限速设置
		return new DownloadServer(1_000_000, 1_000_000)
				// 文件下载
				.download(FILE_PATH, dataBufferFactory, rangeHeader);
	}

}
