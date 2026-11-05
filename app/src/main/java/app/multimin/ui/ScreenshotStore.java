package app.multimin.ui;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;

final class ScreenshotStore {
    static final long RETENTION=Duration.ofDays(7).toMillis();
    final Path directory;
    record Photo(Path path,long created,boolean kept) {
        long remaining(long now){return Math.max(0,created+RETENTION-now);}
        String shortTime(long now){if(kept)return "Kept";long m=remaining(now)/60000;return m>=1440?(m/1440)+"D":m>=60?(m/60)+"h":m>0?m+"m":"<1m";}
        String time(long now){if(kept)return "Kept · no automatic deletion";long m=remaining(now)/60000;if(m==0)return "Deletes in less than one minute";return "Deletes in "+(m>=1440?(m/1440)+" days, ":"")+(m>=60?((m/60)%24)+" hours, ":"")+(m%60)+" minutes";}
    }
    ScreenshotStore(){this(Path.of(System.getenv().getOrDefault("LOCALAPPDATA",Path.of(System.getProperty("user.home"),".local","share").toString()),"MultiMin","screenshots"));}
    ScreenshotStore(Path directory){this.directory=directory.toAbsolutePath().normalize();}
    private interface Operation<T>{T run() throws IOException;}
    private static final Object MUTEX=new Object();
    private <T>T locked(Operation<T> action)throws IOException{
        synchronized(MUTEX){Files.createDirectories(directory);try(FileChannel channel=FileChannel.open(directory.resolve(".screenshots.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock(0,1,false)){return action.run();}}
    }
    java.util.List<Photo> list()throws IOException{return locked(()->{
        ArrayList<Photo> result=new ArrayList<>();long now=System.currentTimeMillis();
        try(var files=Files.newDirectoryStream(directory,"snapshot-*.png")){for(Path path:files){if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;long t=Files.getLastModifiedTime(path).toMillis();boolean kept=Files.exists(marker(path));if(!kept&&t+RETENTION<=now){Files.deleteIfExists(path);continue;}result.add(new Photo(path,t,kept));}}
        result.sort(Comparator.comparingLong(Photo::created).reversed());return result;
    });}
    Path save(java.awt.image.BufferedImage image)throws IOException {
        Files.createDirectories(directory);
        Path output=directory.resolve("snapshot-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".png");
        Path temporary=Files.createTempFile(directory,".capture-",".tmp");
        try {
            if(!javax.imageio.ImageIO.write(image,"png",temporary.toFile()))throw new IOException("PNG encoder unavailable");
            locked(()->{try{Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,output);}return null;});return output;
        } finally {Files.deleteIfExists(temporary);}
    }
    private Path marker(Path path){return path.resolveSibling(path.getFileName()+".keep");}
    private void validate(Path path)throws IOException{if(!directory.equals(path.toAbsolutePath().normalize().getParent())||Files.isSymbolicLink(path)||!path.getFileName().toString().matches("snapshot-.*\\.png"))throw new IOException("Invalid screenshot path");}
    void keep(Photo photo,boolean keep)throws IOException{locked(()->{validate(photo.path);if(!Files.isRegularFile(photo.path))throw new IOException("Screenshot no longer exists");if(keep)Files.writeString(marker(photo.path),"Kept by user\n");else{Files.setLastModifiedTime(photo.path,FileTime.fromMillis(System.currentTimeMillis()));Files.deleteIfExists(marker(photo.path));}return null;});}
    void delete(Photo photo)throws IOException{locked(()->{validate(photo.path);Files.deleteIfExists(photo.path);Files.deleteIfExists(marker(photo.path));return null;});}
}
