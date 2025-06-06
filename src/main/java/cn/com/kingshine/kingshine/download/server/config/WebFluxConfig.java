package cn.com.kingshine.kingshine.download.server.config;

import io.netty.buffer.ByteBufAllocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * @author 张福兴
 * @version 1.0
 * @date 2025/5/29
 * @email zhangfuxing1010@163.com
 */
// 5. 配置类
@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

	@Override
	public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
		// 增加缓冲区大小以支持大文件
		configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024); // 16MB
	}

	@Bean
	public DataBufferFactory dataBufferFactory() {
		// 使用NettyDataBufferFactory以获得更好的内存管理
		return new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
	}
}
