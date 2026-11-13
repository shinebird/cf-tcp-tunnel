package uk.ayachi.cftunnel.core;

import java.util.ArrayDeque;

/** Bounded FIFO. Reader callbacks never block on slow TCP consumers. */
public final class ByteQueue<T> {
    private static final class Item<T>{ final T item;final int bytes;Item(T t,int n){item=t;bytes=n;} }
    private final ArrayDeque<Item<T>> q=new ArrayDeque<>();
    private final int maxBytes,maxItems; private int bytes; private boolean ended;
    public ByteQueue(int maxBytes,int maxItems){this.maxBytes=maxBytes;this.maxItems=maxItems;}
    public synchronized boolean offer(T value,int size){
        if(ended||size<0||size>maxBytes-bytes||q.size()>=maxItems)return false;
        q.add(new Item<>(value,size));bytes+=size;notifyAll();return true;
    }
    public synchronized T take() throws InterruptedException {
        while(q.isEmpty()&&!ended)wait();if(q.isEmpty())return null;
        Item<T> x=q.remove();bytes-=x.bytes;return x.item;
    }
    public synchronized void finish(){ended=true;notifyAll();}
    public synchronized void abort(){ended=true;q.clear();bytes=0;notifyAll();}
    public synchronized int bytes(){return bytes;}
}
