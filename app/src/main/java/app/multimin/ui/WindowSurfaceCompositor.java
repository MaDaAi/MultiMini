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
    private long owner;
    private boolean failed;
    void reset() { close();failed=false; }

    void update(PreviewPanel panel,View view,Rectangle canvas,boolean locked,IntFunction<Rectangle> bounds) {
        boolean eligible=locked && panel.isShowing() && Boolean.getBoolean("multimin.nativeWindowPresentation")
            && !Boolean.getBoolean("multimin.disableNativeComposition") && !failed
            && GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length==1;
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
                    if(thumbnail==0){failed=true;close();return;}
                    entries.add(new Entry(source,source.nativeWindowHandle,thumbnail));
                }
            }
            for(Entry entry:entries) {
                Source source=entry.source();long size=NativeWindowCompositor.size(entry.thumbnail());
                int width=(int)(size>>>32),height=(int)size;
                if(width<=0 || height<=0){failed=true;close();return;}
                source.nativeSourceWidth=width;source.nativeSourceHeight=height;
                Rectangle dest=bounds.apply(view.sources.indexOf(source));dest.translate(location.x,location.y);
                dest=PreviewPanel.toPhysicalScreenBounds(dest,panel.getGraphicsConfiguration());
                Rectangle clip=dest.intersection(viewport);
                if(clip.isEmpty()){failed=true;close();return;}
                int left=(int)Math.round((clip.x-dest.x)*(double)width/dest.width);
                int top=(int)Math.round((clip.y-dest.y)*(double)height/dest.height);
                int right=(int)Math.round((clip.x+clip.width-dest.x)*(double)width/dest.width);
                int bottom=(int)Math.round((clip.y+clip.height-dest.y)*(double)height/dest.height);
                if(!NativeWindowCompositor.update(entry.thumbnail(),owner,clip.x,clip.y,clip.width,clip.height,
                    left,top,right-left,bottom-top,Math.max(0,Math.min(255,source.opacity*255/100)))) {failed=true;close();return;}
                source.nativeCompositionActive=true;
                source.standbyFrame=null;
                source.activeCaptureBackend="Native DWM composition · monitor paced · no CPU frames";
            }
        } catch(LinkageError | RuntimeException error){failed=true;close();}
    }

    @Override public void close() {
        for(Entry entry:entries) {
            try{NativeWindowCompositor.unregister(entry.thumbnail());}catch(LinkageError ignored){}
            entry.source().nativeCompositionActive=false;
            entry.source().nativeSourceWidth=0;entry.source().nativeSourceHeight=0;
        }
        entries.clear();owner=0;
    }
}
