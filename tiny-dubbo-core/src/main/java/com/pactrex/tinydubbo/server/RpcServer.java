package com.pactrex.tinydubbo.server;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.pactrex.tinydubbo.annotations.RpcService;
import com.pactrex.tinydubbo.codec.RpcDecoder;
import com.pactrex.tinydubbo.codec.RpcEncoder;
import com.pactrex.tinydubbo.handler.RpcServerHandler;
import com.pactrex.tinydubbo.model.RpcRequest;
import com.pactrex.tinydubbo.model.RpcResponse;
import com.pactrex.tinydubbo.registry.ServiceRegistry;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class RpcServer implements ApplicationContextAware,InitializingBean{
	
	private static final Logger LOGGER = LoggerFactory
			.getLogger(RpcServer.class);

	private String serverAddress;
	private ServiceRegistry serviceRegistry;

	//用于存储业务接口和实现类的实例对象(由spring所构造)
	private Map<String, Object> handlerMap = new HashMap<String, Object>();

	public RpcServer(String serverAddress) {
		this.serverAddress = serverAddress;
	}

	//服务器绑定的地址和端口由spring在构造本类时从配置文件中传入
	public RpcServer(String serverAddress, ServiceRegistry serviceRegistry) {
		this.serverAddress = serverAddress;
		//用于向zookeeper注册名称服务的工具类
		this.serviceRegistry = serviceRegistry;
	}

	/**
	 * 通过注解，获取标注了rpc服务注解的业务类的----接口及impl对象，将它放到handlerMap中
	 */
	public void setApplicationContext(ApplicationContext ctx)
			throws BeansException {
		Map<String, Object> serviceBeanMap = ctx
				.getBeansWithAnnotation(RpcService.class);
		if (MapUtils.isNotEmpty(serviceBeanMap)) {
			for (Object serviceBean : serviceBeanMap.values()) {
				//从业务实现类上的自定义注解中获取到value，从来获取到业务接口的全名
				String interfaceName = serviceBean.getClass()
						.getAnnotation(RpcService.class).value().getName();
				// 存入用户加了自定义注解的业务实现及其接口名称
				handlerMap.put(interfaceName, serviceBean);
			}
		}
	}

	/**
	 * 在此启动netty服务，绑定handler流水线：
	 * 1、接收请求数据进行反序列化得到request对象
	 * 2、根据request中的参数，让RpcHandler从handlerMap中找到对应的业务imple，调用指定方法，获取返回结果
	 * 3、将业务调用结果封装到response并序列化后发往客户端
	 *
	 */
	public void afterPropertiesSet() throws Exception {
		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		try {
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap
					.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel channel)
								throws Exception {
							channel.pipeline()
									.addLast(new RpcDecoder(RpcRequest.class))// 注册解码 IN-1
									.addLast(new RpcEncoder(RpcResponse.class))// 注册编码 OUT
									.addLast(new RpcServerHandler(handlerMap));//注册RpcHandler IN-2
						}
					}).option(ChannelOption.SO_BACKLOG, 128)
					.childOption(ChannelOption.SO_KEEPALIVE, true);

			
			String[] array = serverAddress.split(":");
			String host = array[0];
			int port = Integer.parseInt(array[1]);

			ChannelFuture future = bootstrap.bind(host, port).sync();
			LOGGER.debug("server started on port {}", port);

			/**
			 * 向zookeeper注册服务
			 */
			if (serviceRegistry != null) {
				serviceRegistry.register(serverAddress);
			}

			future.channel().closeFuture().sync();
		} finally {
			workerGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
		}
	}

}
