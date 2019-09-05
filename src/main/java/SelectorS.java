import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

/**
 * nio 中相关核心类方法
 *
 * {@link Selector} 轮询注册器
 *      {@link Selector#open()}
 *          新建（打开）一个 selector 对象实例
 *      {@link Selector#isOpen()}
 *          返回当前的 selector 是否是开启状态
 *      {@link Selector#keys()}
 *          返回所有注册到这个 selector 上的 key 集合，每一个 key 都有对应的 channel 和 感兴趣的 ops
 *          这个集合不能直接删除，否则抛出异常；只有该 selector 被取消或者对应的 channel 取消注册，才会删除这个集合
 *      {@link Selector#selectedKeys()}
 *          该方法不会阻塞，就是返回已经就绪的 key 集合，一般都是在 .select() 之后调用，因为有 IO 事件发生，
 *          如果直接调用，则返回空集合
 *      {@link Selector#selectNow()}
 *          非阻塞获取已经就绪的 key 集合
 *          此方法会消耗 wakeup 作用
 *      {@link Selector#select()}
 *          阻塞式等待 IO 事件发生
 *          阻塞中的线程被唤醒有四种情况：
 *              1 发生了 IO 事件
 *              2 调用了 wakeup 方法
 *              3 线程设置了中断信号
 *              4 调用 close 方法，但是会导致在 .select 方法出抛出异常
 *      {@link Selector#select(long)}
 *          阻塞指定时间等待 IO 事件发生
 *          阻塞中的线程被唤醒有四种情况：
 *              1 发生了 IO 事件
 *              2 调用了 wakeup 方法
 *              3 线程设置了中断信号
 *              4 调用 close 方法，但是会导致在 .select 方法出抛出异常
 *      {@link Selector#wakeup()}
 *          selector 会在调用 select 方法阻塞，如果需要唤醒，则调用 wakeup 方法
 *          如果线程已经阻塞，则效果是立马返回；
 *          如果线程当前没有阻塞，则效果是调用 select 方法会里面返回
 *          需要注意：调用 selectNow 方法会消耗一次二元信号
 *      {@link Selector#close()}
 *          关闭 selector
 *          如果一个线程当前在这个选择器的选择方法中被阻塞，即调用 select 方法，那么它就会像调用选择器的 wakeup 方法一样被中断唤醒
 *          仍然与此选择器关联的任何未取消的键将无效，它们的通道将注销，与此选择器关联的任何其他资源将被释放
 *          如果这个选择器已经关闭，那么调用再次这个方法不会报错，而是什么都不做
 *          关闭选择器之后，除了调用此方法 close 或唤醒方法 wakeup 之外，调用任何其它方法都会抛出异常
 *
 *
 *
 * {@link SelectionKey} 可以理解为它就是一个 channel 和 感兴趣的 ops 的封装体，然后注册在 selector 上
 *      {@link SelectionKey#channel()}
 *          返回此键对应的通道 channel。即使取消了 key，该方法仍将继续返回通道 channel
 *      {@link SelectionKey#selector()}
 *          返回此键对应的选择器 selector。即使取消了键，该方法也将继续返回选择器 selector
 *      {@link SelectionKey#isValid()}
 *          表示当前这个 key 是否有效。
 *          key 在创建时是有效的，并且在被取消、关闭其通道或关闭其选择器之前一直有效，否则返回无效
 *      {@link SelectionKey#cancel()}
 *          取消此 key 的通道与其选择器的注册。
 *          该操作后将该键视为无效，并添加到其选择器的 canceled -key 集中。
 *          在下一次选择操作期间，该键将从该选择器的所有键集中删除，就是 {@link Selector#keys()}
 *          该方法可以被重复调用
 *      {@link SelectionKey#interestOps()}
 *          返回当前这个 key 上感兴趣的 ops
 *      {@link SelectionKey#interestOps(int)}
 *          将此 key 的兴趣值 ops 设置为给定值
 *          一旦设置后，那么下次 select 就会立马生效
 *      {@link SelectionKey#readyOps()}
 *          返回这个 key 已经就绪的 ops，就是发生的 IO 事件对应的 ops
 *          这个方法一般不会使用，因为作者已经提供下面四个简便方法，不然开发者还要进行位计算
 *      {@link SelectionKey#isReadable()}
 *      {@link SelectionKey#isWritable()}
 *      {@link SelectionKey#isConnectable()}
 *      {@link SelectionKey#isAcceptable()}
 *      {@link SelectionKey#attach(Object)}
 *          以后可以通过附件方法检索所附对象。一次只能附着一个物体;调用此方法会导致丢弃任何以前的附件。通过附加 null 可以丢弃当前附件。
 *          还有一种设置附件方式，
 *              {@link java.nio.channels.ServerSocketChannel#register(Selector, int, Object)}
 *              {@link java.nio.channels.SocketChannel#register(Selector, int, Object)}
 *      {@link SelectionKey#attachment()}
 *          返回当前附件
 *
 *
 */
public class SelectorS {


}
