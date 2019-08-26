import org.apache.coyote.http11.filters.ChunkedOutputFilter;

/**
 * http study
 *
 *  HTTP1.x： 一次请求-响应，建立一个连接，用完关闭；每一个请求都要建立一个连接
 *  HTTP/1.1：Pipeling 解决方式为，若干个请求排队串行化单线程处理，后面的请求等待前面请求的返回才能获得执行机会，
 *            一旦有某请求超时等，后续请求只能被阻塞，毫无办法，也就是人们常说的线头阻塞；
 *  HTTP/2：多个请求可同时在一个连接上并行执行。某个请求任务耗时严重，不会影响到其它连接的正常执行
 *
 *  HTTP/2 新特性
 *      1 服务端推送，也就是把客户端所需要的资源伴随着index.html一起发送到客户端，省去了客户端重复请求的步骤
 *      2 头部压缩，HTTP2.0 可以维护一个字典，差量更新 HTTP 头部，大大降低因头部传输产生的流量
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
@SuppressWarnings("all")
public class HttpS {


    /* 响应头 Transfer-Encoding: chunked */
    /**
     * http 请求中，由于有 keep-alive 存在，一个连接可以发送多次 请求-响应，就会出现如何控制哪一次请求结束，哪一次响应结束
     * 请求中，内容长度是通过 Content-Length 来指定的，这样服务端就可以根据这个头信息进行获取内容读取，如果读取字节不够，就 pending
     * 响应中，内容其实也可以通过 Content-Length 来指定的，但是有些时候，服务端在写的时候并不能很好的获取这个内容长度：
     *   e.g.
     *      比如响应一个文件内容，该文件来自网络中，就必须先在服务端开一个足够大的 buffer 进行缓存，
     *      计算内容字节大小，既浪费时间又浪费空间
     * 请求头 Transfer-Encoding: chunked （分块编码）就是用来解决这个问题的
     * 原理及使用：
     *      1 每一次响应中都必须添加响应头 Transfer-Encoding: chunked
     *      2 每个分块包含十六进制的长度值和数据，长度值独占一行，长度不包括它结尾的 CRLF（\r\n），也不包括分块数据结尾的 CRLF。
     *        最后一个分块长度值必须为 0，对应的分块数据没有内容，表示实体结束
     *      3 如果浏览器接收到很多 chunk，它会自己判断，哪一个 chunk 后面会有 0\r\n\r\n，如果有则表示一个响应结束
     *        tomcat 中这个类就在 {@link ChunkedOutputFilter#END_CHUNK_BYTES}
     *        所以测试下来，只要输入这个 END_CHUNK_BYTES，不管是浏览器还是 postman，都会立即接收到响应
     *
     *
     *
     */












}
