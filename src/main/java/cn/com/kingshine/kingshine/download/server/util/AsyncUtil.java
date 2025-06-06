package cn.com.kingshine.kingshine.download.server.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * @author 张福兴
 * @version 1.0
 * @date 2025/6/5
 * @email zhangfuxing1010@163.com
 */
public class AsyncUtil {
	public static CompletableFuture<Void> allOf(Executor executor, Runnable... runnables) {
		CompletableFuture<?>[] futures = getCompletableFutures(executor, runnables);
		return CompletableFuture.allOf(futures);
	}

	public static CompletableFuture<Void> allOf(Executor executor, Supplier<?>... suppliers) {
		CompletableFuture<?>[] futures = getCompletableFutures(executor, suppliers);
		return CompletableFuture.allOf(futures);
	}

	public static CompletableFuture<Object> anyOf(Executor executor, Runnable... runnables) {
		CompletableFuture<?>[] futures = getCompletableFutures(executor, runnables);
		return CompletableFuture.anyOf(futures);
	}

	public static CompletableFuture<Object> anyOf(Executor executor, Supplier<?>... suppliers) {
		CompletableFuture<?>[] futures = getCompletableFutures(executor, suppliers);
		return CompletableFuture.anyOf(futures);
	}


	private static CompletableFuture<?>[] getCompletableFutures(Executor executor, Runnable[] runnables) {
		CompletableFuture<?>[] futures = new CompletableFuture[runnables.length];
		for (int i = 0; i < runnables.length; i++) {
			futures[i] = CompletableFuture.runAsync(runnables[i], executor);
		}
		return futures;
	}

	private static CompletableFuture<?>[] getCompletableFutures(Executor executor, Supplier<?>[] suppliers) {
		CompletableFuture<?>[] futures = new CompletableFuture[suppliers.length];
		for (int i = 0; i < suppliers.length; i++) {
			futures[i] = CompletableFuture.supplyAsync(suppliers[i], executor);
		}
		return futures;
	}
}
