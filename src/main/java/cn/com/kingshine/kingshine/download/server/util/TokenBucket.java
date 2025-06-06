package cn.com.kingshine.kingshine.download.server.util;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 基于令牌桶算法的限速工具类，用于控制数据流速率。
 * <p>
 * 令牌桶算法核心思想：
 * <p>
 * 1. 桶以固定速率补充令牌（refillRatePerSecond）
 * <p>
 * 2. 数据消费时需先获取对应数量的令牌
 * <p>
 * 3. 若令牌不足则等待补充（通过 Mono.delay 实现非阻塞等待）
 *
 * @author 张福兴
 * @version 1.0
 * @date 2025/6/6
 * @email zhangfuxing1010@163.com
 */
public class TokenBucket {
	/**
	 * 桶的最大容量（单位：字节/令牌数）
	 * <p>
	 * 示例：1_000_000 表示桶最多容纳 1MB 的令牌
	 */
	private final AtomicLong capacity = new AtomicLong();

	/**
	 * 每秒补充的令牌数（单位：字节/令牌数）
	 * <p>
	 * 示例：1_000_000 表示每秒补充 1MB 的令牌
	 */
	private final AtomicLong refillRatePerSecond = new AtomicLong();

	/**
	 * 当前桶内剩余令牌数（单位：字节/令牌数）
	 */
	private final AtomicLong tokens = new AtomicLong();

	/**
	 * 上次补充令牌的时间戳（单位：毫秒）
	 */
	private volatile long lastRefillTimestamp;

	/**
	 * 初始化令牌桶
	 *
	 * @param capacity            桶容量（单位：字节/令牌数）
	 * @param refillRatePerSecond 每秒补充的令牌数（单位：字节/令牌数）
	 *                            <p>
	 *                            示例：new TokenBucket(1_000_000, 500_000) 创建一个最大 1MB 容量的桶，每秒补充 500KB 令牌
	 */
	public TokenBucket(long capacity, long refillRatePerSecond) {
		if (capacity <= 0 || refillRatePerSecond <= 0) {
			throw new IllegalArgumentException("Capacity and refill rate must be positive");
		}
		this.capacity.set(capacity);
		this.refillRatePerSecond.set(refillRatePerSecond);
		tokens.set(capacity);
		lastRefillTimestamp = System.currentTimeMillis();
	}

	public synchronized void setCapacity(long capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
		this.capacity.set(capacity);
		if (tokens.get() > capacity) tokens.set(capacity);
	}

	public synchronized void setRefillRatePerSecond(long rate) {
		if (rate <= 0) throw new IllegalArgumentException("Rate must be positive");
		refillRatePerSecond.set(rate);
	}

	public synchronized long getAvailableTokens() {
		refill();
		return tokens.get();
	}

	public synchronized long getCapacity() {
		return capacity.get();
	}

	/**
	 * 消费指定数量的令牌（非阻塞）
	 *
	 * @param tokensNeeded 需要消费的令牌数
	 * @return Mono<Void> 当令牌足够时立即完成，否则返回延迟任务
	 * <p>
	 * 注意事项：
	 * <p>
	 * - 使用 synchronized 确保线程安全
	 * <p>
	 * - 返回的 Mono 需要通过 flatMap 调用，避免阻塞 Reactor 线程
	 */
	public Mono<Void> consume(long tokensNeeded) {
		if (tokensNeeded < 0) {
			throw new IllegalArgumentException("Tokens needed must be non-negative");
		}

		refill();
		long currentTokens = tokens.get();
		if (currentTokens >= tokensNeeded) {
			tokens.compareAndSet(currentTokens, currentTokens - tokensNeeded);
			return Mono.empty();
		}

		long millisToWait = (long) ((double) (tokensNeeded - currentTokens) / refillRatePerSecond.get() * 1000);
		tokens.set(0);
		return Mono.delay(Duration.ofMillis(millisToWait)).then();
	}

	/**
	 * 同步方法，阻塞当前线程
	 *
	 * @param tokensNeeded 需要的令牌数
	 * @throws InterruptedException 中断异常
	 */
	public void consumeSync(long tokensNeeded) throws InterruptedException {
		if (tokensNeeded < 0) {
			throw new IllegalArgumentException("Tokens needed must be non-negative");
		}

		refill();
		long currentTokens = tokens.get();
		if (currentTokens >= tokensNeeded) {
			tokens.compareAndSet(currentTokens, currentTokens - tokensNeeded);
			return;
		}

		long millisToWait = (long) ((double) (tokensNeeded - currentTokens) / refillRatePerSecond.get() * 1000);
		tokens.set(0);
		Thread.sleep(millisToWait); // 同步阻塞等待
	}

	/**
	 * 根据时间差补充令牌（内部方法）
	 * <p>
	 * 计算从上次补充到当前时间的间隔，并按 refillRatePerSecond 补充令牌
	 * <p>
	 * 最终令牌数不会超过 capacity
	 */
	private void refill() {
		long now = System.currentTimeMillis();
		long elapsedSeconds = (now - lastRefillTimestamp) / 1000;
		if (elapsedSeconds > 0) {
			try {
				long addedTokens = Math.multiplyExact(elapsedSeconds, refillRatePerSecond.get());
				long newTokens = Math.min(capacity.get(), tokens.get() + addedTokens);
				tokens.set(newTokens);
				lastRefillTimestamp = now;
			} catch (ArithmeticException e) {
				// 处理溢出
			}
		}
	}

	/**
	 * 组合桶
	 */
	public static class CompositeTokenBucket {
		private static final Logger log = LoggerFactory.getLogger(CompositeTokenBucket.class);
		private final CopyOnWriteArrayList<TokenBucket> buckets = new CopyOnWriteArrayList<>();
		private CompositeTokenBucket.ConsumptionStrategy strategy = CompositeTokenBucket.ConsumptionStrategy.SEQUENTIAL;
		private TokenBucket masterBucket;

		// 动态调整参数
		public void setCapacity(long newCapacity) {
			buckets.forEach(b -> b.setCapacity(newCapacity));
		}

		public void setRefillRate(long newRate) {
			buckets.forEach(b -> b.setRefillRatePerSecond(newRate));
		}

		// 实时监控
		public long getTotalAvailableTokens() {
			return buckets.stream().mapToLong(TokenBucket::getAvailableTokens).sum();
		}

		public List<String> getBucketStatus() {
			return buckets.stream()
					.map(b -> String.format("容量: %d, 可用: %d",
							b.getCapacity(), b.getAvailableTokens()))
					.toList();
		}

		// 策略模式
		public enum ConsumptionStrategy {
			SEQUENTIAL, PARALLEL, PRIORITY
		}

		public void setStrategy(CompositeTokenBucket.ConsumptionStrategy strategy) {
			this.strategy = strategy;
		}

		public void addBucket(TokenBucket bucket) {
			buckets.add(bucket);
		}

		// 自定义错误处理
		@FunctionalInterface
		public interface BucketErrorHandler {
			Publisher<? extends Void> handleError(Throwable error);
		}

		private CompositeTokenBucket.BucketErrorHandler customErrorHandler = (e) -> {
			log.warn("自定义处理 - 桶异常: {}", e.getMessage());
			return Mono.empty();
		};

		public void setErrorHandler(CompositeTokenBucket.BucketErrorHandler handler) {
			this.customErrorHandler = handler;
		}

		// 主从结构
		public void setMasterBucket(TokenBucket master) {
			this.masterBucket = master;
		}

		// 核心消费方法
		public Mono<Void> consume(long tokensNeeded) {
			return Mono.defer(() -> {
				if (masterBucket != null) {
					return masterBucket.consume(tokensNeeded);
				}
				return Mono.empty();
			}).then(Mono.whenDelayError(
					Flux.fromStream(getBucketsByStrategy().stream())
							.flatMap(b -> processBucket(b, tokensNeeded))
							.onErrorResume(customErrorHandler::handleError)
			));
		}

		private List<TokenBucket> getBucketsByStrategy() {
			return switch (strategy) {
				case PARALLEL -> new ArrayList<>(buckets);
				case PRIORITY -> buckets.stream()
						.sorted(Comparator.comparingLong(TokenBucket::getAvailableTokens).reversed())
						.collect(Collectors.toList());
				default -> new ArrayList<>(buckets);
			};
		}

		private Publisher<? extends Void> handleBucketError(Throwable e) {
			log.warn("检测到令牌桶消费异常，将继续处理其他桶", e);
			return Mono.empty();
		}

		private Mono<Void> processBucket(TokenBucket bucket, long tokensNeeded) {
			return bucket.consume(tokensNeeded)
					.doOnError(e -> log.error("令牌桶消费失败: 需要 {} 个令牌", tokensNeeded, e));
		}

		public static void main(String[] args) {
			// 创建复合桶
			CompositeTokenBucket composite = new CompositeTokenBucket();

			// 添加基础桶
			composite.addBucket(new TokenBucket(1_000_000, 500_000)); // 限速 500KB/s
			composite.addBucket(new TokenBucket(100, 10)); // 限速 10 请求/秒

			// 设置主桶控制总体流量
			composite.setMasterBucket(new TokenBucket(5_000_000, 2_000_000));

			// 启用并行策略
			composite.setStrategy(CompositeTokenBucket.ConsumptionStrategy.PARALLEL);

			// 自定义错误处理
			composite.setErrorHandler((error) -> {
				System.out.println("自定义错误处理: " + error.getMessage());
				return Mono.empty();
			});

			// 动态调整参数
			composite.setCapacity(2_000_000); // 将所有桶容量提升至 2MB

			// 执行限速消费
			composite.consume(4096)
					.then()
					.block(); // 等待消费完成
		}
	}
}
