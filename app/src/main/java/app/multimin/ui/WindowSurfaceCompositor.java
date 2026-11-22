package app.multimin.ui;

import app.multimin.backend.NativeWindowCompositor;
import app.multimin.model.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.IntFunction;

/** Native presentation for eligible window-only multitask compositions. EDT owned. */
final class WindowSurfaceCompositor implements AutoCloseable {
    private record Entry(Source source,long window,long thumbnail) {}
    private final List<Entry> entries=new ArrayList<>();
    private record Placement(Rectangle viewport,Rectangle clip,int left,int top,int width,int height,int opacity) {}
    private final Map<Long,Placement> placements=new HashMap<>();
    private long owner;
    // A failed native registration used to disable DWM presentation for the whole
    // multitask session. On first entry sources are often still minimized or being
    // restored when the first paint runs, so the session stayed on the slower
    // capture renderer until the next entry. Retry with a growing backoff instead.
    private long retryAtNanos;
    private long backoffNanos=INITIAL_BACKOFF;
    private static final long INITIAL_BACKOFF=400_000_000L, MAX_BACKOFF=5_000_000_000L;
    void reset() { close();retryAtNanos=0;backoffNanos=INITIAL_BACKOFF; }
    private void fail() {
        close();
        retryAtNanos=System.nanoTime()+backoffNanos;
        backoffNanos=Math.min(MAX_BACKOFF,backoffNanos*2);
    }
    /** True while waiting to retry native presentation after a failure. */
    boolean coolingDown() { return retryAtNanos!=0 && System.nanoTime()<retryAtNanos; }
    /** A retry is due: the next paint should attempt native presentation again. */
    boolean retryDue() { return retryAtNanos!=0 && System.nanoTime()>=retryAtNanos; }

    void update(PreviewPanel panel,View view,Rectangle canvas,boolean locked,IntFunction<Rectangle> bounds) {
        boolean eligible=locked && panel.isShowing() && Boolean.getBoolean("multimin.nativeWindowPresentation")
            && !Boolean.getBoolean("multimin.disableNativeComposition") && !coolingDown();
        List<Source> sources=new ArrayList<>();
        if(eligible) for(int i=view.sources.size()-1;i>=0;i--) {
            Source source=view.sources.get(i);
            if(!panel.isSourceRendered(i))continue;
            if(!source.usesWindowTarget() || !source.nativePresentationEnabled || source.captureCursor || source.nativeWindowHandle==0
                || !(source.captureMethod.equals("AUTO") || source.captureMethod.equals("WGC"))){eligible=false;break;}
            sources.add(source);
        }
        if(!eligible || sources.isEmpty()){close();return;}
        try {
            if(!NativeWindowCompositor.available()){close();return;}
            Point location=panel.getLocationOnScreen();
            Rectangle viewport=PreviewPanel.toPhysicalScreenBounds(new Rectangle(location.x+canvas.x,location.y+canvas.y,canvas.width,canvas.height),panel.getGraphicsConfiguration());
            long destination=NativeWindowCompositor.destination(viewport.x,viewport.y,viewport.width,viewport.height);
            if(destination==0){close();return;}
            boolean rebuild=owner!=destination || sources.size()!=entries.size();
            if(!rebuild)for(int i=0;i<sources.size();i++)if(entries.get(i).source()!=sources.get(i)
                || entries.get(i).window()!=sources.get(i).nativeWindowHandle){rebuild=true;break;}
            if(rebuild) {
                close();owner=destination;
                for(Source source:sources) {
                    long thumbnail=NativeWindowCompositor.register(owner,source.nativeWindowHandle);
                    if(thumbnail==0){fail();return;}
                    entries.add(new Entry(source,source.nativeWindowHandle,thumbnail));
                }
            }
            for(Entry entry:entries) {
                Source source=entry.source();long size=NativeWindowCompositor.size(entry.thumbnail());
                int width=(int)(size>>>32),height=(int)size;
                if(width<=0 || height<=0){fail();return;}
                source.nativeSourceWidth=width;source.nativeSourceHeight=height;
                Rectangle dest=bounds.apply(view.sources.indexOf(source));dest.translate(location.x,location.y);
                dest=PreviewPanel.toPhysicalScreenBounds(dest,panel.getGraphicsConfiguration());
                Rectangle clip=dest.intersection(viewport);
                if(clip.isEmpty()){fail();return;}
                int left=(int)Math.round((clip.x-dest.x)*(double)width/dest.width);
                int top=(int)Math.round((clip.y-dest.y)*(double)height/dest.height);
                int right=(int)Math.round((clip.x+clip.width-dest.x)*(double)width/dest.width);
                int bottom=(int)Math.round((clip.y+clip.height-dest.y)*(double)height/dest.height);
                int opacity=Math.max(0,Math.min(255,source.opacity*255/100));
                Placement placement=new Placement(viewport,clip,left,top,right-left,bottom-top,opacity);
                if(!placement.equals(placements.get(entry.thumbnail()))) {
                    if(!NativeWindowCompositor.update(entry.thumbnail(),owner,clip.x,clip.y,clip.width,clip.height,
                        left,top,right-left,bottom-top,opacity)) {fail();return;}
                    placements.put(entry.thumbnail(),placement);
                }
                source.nativeCompositionActive=true;
                source.standbyFrame=null;
                source.activeCaptureBackend="Native DWM composition · monitor paced · no CPU frames";
            }
            retryAtNanos=0;backoffNanos=INITIAL_BACKOFF;
        } catch(LinkageError | RuntimeException error){fail();}
    }

    @Override public void close() {
        for(Entry entry:entries) {
            try{NativeWindowCompositor.unregister(entry.thumbnail());}catch(LinkageError ignored){}
            entry.source().nativeCompositionActive=false;
            entry.source().nativeSourceWidth=0;entry.source().nativeSourceHeight=0;
        }
        entries.clear();placements.clear();owner=0;
    }
}
