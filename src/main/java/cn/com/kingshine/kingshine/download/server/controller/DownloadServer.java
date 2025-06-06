package cn.com.kingshine.kingshine.download.server.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DownloadServer {
	final static String paramName = "filePath";
	static int downloadSpeed = 0;
	static String log = "./download-server.log";
	static boolean logAppend = true;
	static File logFile;
	static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	static boolean consoleLog = false;
	static boolean enableEncrypt = false;
	static String key = "";
	static boolean encHex = true;

	static String hostname = "0.0.0.0";
	static int port = 20000;
	static int backlog = 0;

	public static void main(String[] args) {
		processCommandArgs(args);

		try {
			startHttpServer(hostname, port, backlog);
		} catch (IOException e) {
			System.out.printf("Http Server启动失败：%s%n".formatted(e.getMessage()));
			System.exit(-1);
		}

		System.out.printf("服务启动成功: bind-address=%s port=%s backlog=%s%n", hostname, port, backlog);
	}

	private static void processCommandArgs(String[] args) {
		for (String arg : args) {
			if (arg.startsWith("-hostname=")) {
				hostname = arg.split("=")[1];
			}
			//
			else if (arg.startsWith("-port=")) {
				port = Integer.parseInt(arg.split("=")[1]);
			}
			//
			else if (arg.startsWith("-backlog=")) {
				backlog = Integer.parseInt(arg.split("=")[1]);
			}
			//
			else if (arg.startsWith("-maxSpeed=")) {
				downloadSpeed = Integer.parseInt(arg.split("=")[1]);
			}
			//
			else if (arg.startsWith("-log=")) {
				log = arg.split("=")[1];
			}
			//
			else if (arg.startsWith("-key=")) {
				key = arg.split("=")[1];
			}
			//
			else if (arg.startsWith("-consoleLog")) {
				consoleLog = Boolean.parseBoolean(arg.split("=")[1]);
			}
			// 密钥
			if (arg.startsWith("-enableEncrypt")) {
				enableEncrypt = Boolean.parseBoolean(arg.split("=")[1]);
			}
			if (arg.startsWith("-encHex")) {
				encHex = Boolean.parseBoolean(arg.split("=")[1]);
			}
			if (arg.startsWith("-logAppend=")) {
				logAppend = Boolean.parseBoolean(arg.split("=")[1]);
			}
		}
		logFile = new File(log);
		Path parent = logFile.toPath().getParent();
		if (!Files.exists(parent)) {
			try {
				Files.createDirectories(parent);
			} catch (IOException e) {
				System.out.printf("日志文件目录创建失败：%s%n".formatted(e.getMessage()));
			}
		}
		if (!logFile.exists()) {
			try {
				Files.createFile(logFile.toPath());
			} catch (IOException e) {
				System.out.printf("日志文件创建失败：%s%n".formatted(e.getMessage()));
			}
		}
		if (enableEncrypt) {
			if (strLen(key) == 0) {
				log("开启加密时必须配置密钥，使用 -key= 指定密钥");
			}
			// 测试密钥
			try {
				String test = "test";
				String encTest = encryptAES(test, key);
				String decTest = decryptAES(encTest, key);
				log("已成功配置密钥: %s -> %s -> %s", test, encTest, decTest);
			} catch (Exception e) {
				log("密钥配置错误，请检查密钥是否正确: %s", e.getMessage());
				System.exit(-1);
			}
		}
	}

	private static void startHttpServer(String hostname, int port, int backlog) throws IOException {
		var httpServer = HttpServer.create();
		httpServer.bind(new java.net.InetSocketAddress(hostname, port), backlog);
		httpServer.createContext("/", indexContext());
		httpServer.createContext("/download/v1", downloadContext());
		httpServer.createContext("/download/v2", renewableDownloadHandler());
		httpServer.start();
		log("Server started at http://%s:%d", hostname, port);
	}

	private static HttpHandler downloadContext() {
		return exchange -> {
			String requestId = UUID.randomUUID().toString().replace("-", "");
			URI uri = exchange.getRequestURI();
			String filepath = getParamValue(paramName, uri);
			log("traceId=%s download v1: uri=%s", requestId, uri);
			if (filepath == null || filepath.isEmpty()) {
				log("traceId=%s download v1 file not found: %s", requestId, filepath);
				exchange.getResponseHeaders().set("Content-Type", "text/html");
				byte[] bytes = "<html><body><h1>400 Not Param filePath</h1></body></html>".getBytes();
				exchange.sendResponseHeaders(400, bytes.length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write(bytes);
				}
				return;
			}
			if (enableEncrypt) {
				filepath = URLDecoder.decode(decryptAES(filepath, key), StandardCharsets.UTF_8);
			}
			if (!new File(filepath).exists()) {
				log("traceId=%s 下载文件不存在：{}", requestId, filepath);
				exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
				byte[] bytes = "<html><body><h1>404 Not Fount File</h1></body></html>".getBytes();
				exchange.sendResponseHeaders(404, bytes.length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write(bytes);
				}
				return;
			}

			Path path = Paths.get(filepath);
			FileChannel open = FileChannel.open(path, StandardOpenOption.READ);
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			// 设置附件名称
			String fileName = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + fileName);
			exchange.sendResponseHeaders(200, open.size());
			ByteBuffer buffer = ByteBuffer.allocate(1024 * 4);
			log("traceId=%s 开始下载文件：%s", requestId, filepath);
			try (OutputStream outputStream = exchange.getResponseBody()) {
				while (open.read(buffer) != -1) {
					buffer.flip();
					outputStream.write(buffer.array(), 0, buffer.limit());
					buffer.clear();
				}
			} finally {
				open.close();
				log("traceId=%s 文件：%s下载完成", requestId, filepath);
			}
		};
	}

	private static HttpHandler indexContext() {
		return exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
			String response = """
					<h1>欢迎使用Http Server文件下载服务!</h1>
					<hr/>
					<p>接口1:</p>
					<p>GET /download/v1?filePath=</p>
					<p> 描述: 文件下载，不支持断点续传</p>
					<hr/>
					<p>接口2:</p>
					<p>GET /download/v2?filePath=</p>
					<p> 描述: 文件下载，支持断点续传</p>
					<hr/>
					""";
			exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
			exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
		};
	}

	private static HttpHandler renewableDownloadHandler() {
		return exchange -> {
			String requestId = UUID.randomUUID().toString().replace("-", "");
			URI uri = exchange.getRequestURI();
			String filepath = getParamValue(paramName, uri);
			log("traceId=%s 可续传下载文件: %s", requestId, filepath);
			if (filepath == null || filepath.isEmpty()) {
				log("断点下载失败，没有传递文件路径参数");
				exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
				byte[] bytes = "<html><body><h1>400 Not Param filePath</h1></body></html>".getBytes();
				exchange.sendResponseHeaders(400, bytes.length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write(bytes);
				}
				return;
			}

			if (enableEncrypt) {
				filepath = URLDecoder.decode(decryptAES(filepath, key), StandardCharsets.UTF_8);
			}

			Headers headers = exchange.getRequestHeaders();
			String rangeHeader = headers.getFirst("Range");
			Path path = Paths.get(filepath);
			if (!Files.exists(path)) {
				log("traceId=%s 下载文件不存在：%s", requestId, filepath);
				exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
				byte[] bytes = "<html><body><h1>404 Not Fount File</h1></body></html>".getBytes();
				exchange.sendResponseHeaders(404, bytes.length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write(bytes);
				}
				return;
			}
			if (Files.isDirectory(path)) {
				log("traceId=%s 下载目录：{}", requestId, path);
				directoryDownload(exchange, path);
				log("traceId=%s 目录下载完成：{}", requestId, path);
				return;
			}

			final long speedLimit = downloadSpeed * 1024L;
			File file = path.toFile();
			try (RandomAccessFile raf = new RandomAccessFile(file, "r");
				 var os = exchange.getResponseBody()) {
				long fileLength = file.length();
				long start = 0;
				long end = fileLength - 1;

				exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
				exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
				exchange.getResponseHeaders().set("Last-Modified", String.valueOf(file.lastModified()));
				exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=" + "\"" + URLEncoder.encode(file.getName(), StandardCharsets.UTF_8) + "\"");
				exchange.getResponseHeaders().set("Content-Length", String.valueOf(end - start + 1));

				// 处理范围请求
				if (rangeHeader != null) {
					log("traceId=%s 开始断点续传：%s", requestId, filepath);
					String[] parts = rangeHeader.substring(rangeHeader.indexOf("=") + 1).split("-");
					start = Long.parseLong(parts[0]);
					if (parts.length > 1) {
						end = Long.parseLong(parts[1]);
					}
					end = Math.min(end, fileLength - 1);
					exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
					exchange.sendResponseHeaders(206, end - start + 1);
				} else {
					log("traceId=%s 可断点续传下载开始：%s", requestId, filepath);
					exchange.sendResponseHeaders(200, fileLength);
				}


				byte[] buffer = new byte[8 * 1024]; // 8KB缓冲区
				long totalRead = 0;
				long maxRead = end - start + 1;

				// 在下载循环中改进限速逻辑
				long interval = 1000L; // 统计窗口1秒
				long startTime = System.currentTimeMillis();
				long transferredBytes = 0;

				while (totalRead < maxRead) {
					int readSize = (int) Math.min(buffer.length, maxRead - totalRead);
					int bytesRead = raf.read(buffer, 0, readSize);
					if (bytesRead == -1) break;

					os.write(buffer, 0, bytesRead);
					totalRead += bytesRead;

					transferredBytes += bytesRead;
					// 限速控制
					if (speedLimit > 0) {
						long elapsed = System.currentTimeMillis() - startTime;
						long expectedTime = (transferredBytes * 1000L) / speedLimit;

						if (elapsed < expectedTime) {
							TimeUnit.MILLISECONDS.sleep(expectedTime - elapsed);
						}

						// 重置统计窗口
						if (elapsed >= interval) {
							startTime = System.currentTimeMillis();
							transferredBytes = 0;
						}
					}
				} // end while

			} catch (IOException e) {
				log("traceId=%s 断点续传下载错误: %s", requestId, e.getMessage());
			} catch (InterruptedException e) {
				log("traceId=%s 断点续传下载中断: %s", requestId, e.getMessage());
			} finally {
				log("traceId=%s 断点下载完成", requestId);
			}
		};
	}

	private static void directoryDownload(HttpExchange exchange, Path path) {
		final byte[] buffer = new byte[1024 * 4];
		try (ZipOutputStream zos = new ZipOutputStream(exchange.getResponseBody());
			 Stream<Path> fileStream = Files.walk(path)) {
			fileStream.filter(p -> !Files.isDirectory(p))
					.map(Path::toFile)
					.forEach(file -> {
						String relativePath = path.relativize(file.toPath()).toString();
						System.out.printf("压缩文件: %s%n", relativePath);
						// 写入文件内容
						try (FileInputStream fis = new FileInputStream(file)) {
							// 创建ZIP条目时使用相对路径
							ZipEntry zipEntry = new ZipEntry(relativePath);
							zos.putNextEntry(zipEntry);

							int length;
							while ((length = fis.read(buffer)) > 0) {
								zos.write(buffer, 0, length);
							}
						} catch (IOException e) {
							System.out.printf("压缩文件失败：file=%s,msg=%s%n", file.getAbsolutePath(), e.getMessage());
						} finally {
							try {
								zos.closeEntry();
							} catch (IOException e) {
								System.out.printf("关闭压缩项异常：msg=%s", e.getMessage());
							}
						}
					});


		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String getParamValue(String paramName, URI uri) {
		String query = uri.getQuery();
		if (query != null) {
			String[] params = query.split("&");
			for (String param : params) {
				String[] kv = param.split("=");
				if (kv.length == 2 && kv[0].equals(paramName)) {
					return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
				}
			}
		}
		return null;
	}

	private static void log(String message, Object... args) {
		String time = formatter.format(LocalDateTime.now());
		try {
			String content = String.format(message, args);
			if (consoleLog) {
				System.out.println(time + " " + content);
			}
			Files.writeString(logFile.toPath(), String.format("%s %s%n", time, content), logAppend ? StandardOpenOption.APPEND : StandardOpenOption.WRITE);
		} catch (IOException e) {
			System.out.printf("%s 日志写出失败：%s, origin=%s", time, e.getMessage(), String.format(message, args));
		}
	}

	private static final String ALGORITHM = "AES";
	private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";


	/**
	 * AES加密（ECB模式）
	 */
	public static String encryptAES(String content, String key) {
		try {
			// 校验密钥长度
			if (key == null || key.isBlank() ||
				(key.length() != 16 && key.length() != 24 && key.length() != 32)) {
				throw new IllegalArgumentException("密钥长度必须为16/24/32字节，当前长度: " + strLen(key));
			}

			// 创建密钥规范
			byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
			SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);

			// 创建并初始化加密器
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, keySpec);

			// 执行加密并转为Base64
			byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
			return encHex ? bytesToHex(encrypted) : Base64.getEncoder().encodeToString(encrypted);
		} catch (Exception e) {
			throw new RuntimeException("AES加密失败", e);
		}
	}

	/**
	 * AES解密（ECB模式）
	 */
	public static String decryptAES(String encryptedData, String key) {
		try {
			// 校验密钥长度
			if (key == null || key.isBlank() ||
				(key.length() != 16 && key.length() != 24 && key.length() != 32)) {
				throw new IllegalArgumentException("密钥长度必须为16/24/32字节，当前长度: " + strLen(key));
			}

			// 创建密钥规范
			byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
			SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);

			// 创建并初始化解密器
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, keySpec);

			// Base64解码并解密
			byte[] decodedBytes = encHex ? hexToBytes(encryptedData) : Base64.getDecoder().decode(encryptedData);
			byte[] decryptedBytes = cipher.doFinal(decodedBytes);

			return new String(decryptedBytes, StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("AES解密失败", e);
		}
	}

	private static int strLen(String str) {
		return str == null ? 0 : str.length();
	}

	public static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xFF));
		}
		return sb.toString();
	}

	public static byte[] hexToBytes(String hex) {
		if (hex == null || hex.isEmpty()) {
			throw new IllegalArgumentException("Input hex string cannot be null or empty.");
		}

		int len = hex.length();
		if (len % 2 != 0) {
			throw new IllegalArgumentException("Hex string must have even length.");
		}

		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
								  + Character.digit(hex.charAt(i + 1), 16));
		}
		return data;
	}

}
