package be.bagofwords.db.experimental.rocksdb;

import be.bagofwords.application.ApplicationContext;
import be.bagofwords.db.DataInterface;
import be.bagofwords.db.DataInterfaceFactory;
import be.bagofwords.db.combinator.Combinator;
import be.bagofwords.util.Utils;
import org.rocksdb.RocksDB;

import java.io.File;

/**
 * Created by Koen Deschacht (koendeschacht@gmail.com) on 9/17/14.
 */
public class RocksDBDataInterfaceFactory extends DataInterfaceFactory {

    private final String directory;
    private final boolean usePatch;

    public RocksDBDataInterfaceFactory(ApplicationContext applicationContext, boolean usePatch) {
        super(applicationContext);
        this.directory = applicationContext.getConfig("data_directory");
        this.usePatch = usePatch;
        File libFile = findLibFile();
        if (libFile == null) {
            throw new RuntimeException("Could not find librocksdbjni.so");
        }
        Utils.addLibraryPath(libFile.getParentFile().getAbsolutePath());
        RocksDB.loadLibrary();
    }

    private File findLibFile() {
        File libFile = new File("./lib/rocksdb/linux-x86_64/librocksdbjni.so");
        if (libFile.exists()) {
            return libFile;
        }
        libFile = new File("./count-db/lib/rocksdb/linux-x86_64/librocksdbjni.so");
        if (libFile.exists()) {
            return libFile;
        }
        return null;
    }

    @Override
    public <T> DataInterface<T> createBaseDataInterface(String nameOfSubset, Class<T> objectClass, Combinator<T> combinator, boolean isTemporaryDataInterface) {
        return new RocksDBDataInterface<>(nameOfSubset, objectClass, combinator, directory, usePatch, isTemporaryDataInterface);
    }
}
