import org.apache.catalina.connector.CoyoteInputStream;
import org.apache.coyote.http11.Http11Processor;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketWrapperBase;

import javax.servlet.http.HttpServletRequest;

/**
 * 读流程
 *  客户端连接的 channel 都是封装在 {@link NioChannel#sc}，而真正读取数据都是在 {@link NioChannel#read} 方法中
 *  第一次读取数据是发生在解析请求行开始 {@link Http11Processor#service(SocketWrapperBase)}，这一次请求会直接读取 8K
 *  封装读取的 buffer 对象是 {@link CoyoteInputStream#ib}
 *
 *  案例：
 *      1 如果应用中直接使用 {@link HttpServletRequest#getInputStream()} 来获取请求体，需要注意：如果请求体小于 8K，
 *        那么在第一次解析请求行的时候就已经全部读取出来的（这里暂时先忽略请求行+请求头+空行，也就是所有数据小于 8K），
 *        所以 {@link NioChannel#read} 方法只会被调用一次；
 *        如果 请求行+请求头+空行+请求体 > 8K，那么应用读取可能采用 .read(byte[], start, length) 方法，这个方法会出现读取泄漏问题，
 *        也就是大于 8K 的数据全部会被忽略，需要应用再次判断是否还有可读的数据才行，比如采用 Content-Length 来判断；
 *        如果采用 .read() 一个一个字节读取，那么就不会出现数据泄漏问题，因为每一次读取都会做一个 check 操作，判断是否还有数据，
 *        其实读取出来的数据都是放在 {@link CoyoteInputStream#ib} 中，当读取到最后一个字节的时候，都会判断 {@link NioChannel#sc}
 *        是否还有数据，其实 .read(byte[], start, length) 方法也会判断，但是只会判断一次，所以读取到最后的时候需要应用自己再判断一次
 *      2 如果应用中使用的类似 spring RequestBody 来接收，那么 spring 会自动判断是否读取数据完毕，不会出现泄漏读取问题
 *        spring 其实也是通过调用 {@link CoyoteInputStream#read(byte[], int, int)} 方法，不管数据是否大于 8K，都会调用方法
 *        进行一次读操作，如果读取出来的有数据，那么就还会进行一次读操作，如果读取出来的字节数为0，才认为读取完毕
 *
 *      总结：不管大小是大于还是小于 8K，应用程序都应该进行读取操作，一般都是调用 {@link CoyoteInputStream#read(byte[], int, int)} 方法
 *            然后根据 Content-Length 进行判断是否读取完毕
 *
 *
 *
 */
@SuppressWarnings("all")
public class ReadingProcess {
}
