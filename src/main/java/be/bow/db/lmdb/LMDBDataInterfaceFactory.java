package be.bow.db.lmdb;

import be.bow.application.memory.MemoryManager;
import be.bow.cache.CachesManager;
import be.bow.db.DataInterface;
import be.bow.db.DataInterfaceFactory;
import be.bow.db.combinator.Combinator;
import org.fusesource.lmdbjni.Env;

import java.io.File;

/**
 * Created by Koen Deschacht (koendeschacht@gmail.com) on 9/16/14.
 */
public class LMDBDataInterfaceFactory extends DataInterfaceFactory {

    private Env env;
    private String directory;

    public LMDBDataInterfaceFactory(CachesManager cachesManager, MemoryManager memoryManager, String directory) {
        super(cachesManager, memoryManager);
        env = new Env();
        env.setMaxDbs(100);
        env.setMapSize(200 * 1024 * 1024); //needs to be quite high, otherwise we get EINVAL or MDB_MAP_FULL errors
        File directoryAsFile = new File(directory);
        if (directoryAsFile.isFile()) {
            throw new RuntimeException("Path " + directoryAsFile.getAbsolutePath() + " is a file! Expected a directory...");
        } else if (!directoryAsFile.exists()) {
            boolean success = directoryAsFile.mkdirs();
            if (!success) {
                throw new RuntimeException("Failed to create directory " + directoryAsFile.getAbsolutePath());
            }
        }
        env.open(directory);
    }

    @Override
    protected <T> DataInterface<T> createBaseDataInterface(String nameOfSubset, Class<T> objectClass, Combinator<T> combinator) {
        return new LMDBDataInterface<>(nameOfSubset, objectClass, combinator, env);
    }

    @Override
    public synchronized void close() {
        super.close();
        env.close();
    }
}
