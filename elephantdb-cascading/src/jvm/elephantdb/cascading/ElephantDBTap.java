package elephantdb.cascading;

import cascading.flow.Flow;
import cascading.flow.FlowListener;
import cascading.flow.FlowProcess;
import cascading.tap.Tap;
import cascading.tap.TapException;
import cascading.tap.hadoop.Hfs;
import cascading.tuple.Fields;
import elephantdb.DomainSpec;
import elephantdb.Utils;
import elephantdb.hadoop.ElephantInputFormat;
import elephantdb.hadoop.ElephantOutputFormat;
import elephantdb.hadoop.LocalElephantManager;
import elephantdb.store.DomainStore;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

public class ElephantDBTap extends Hfs {
    public static final Logger LOG = Logger.getLogger(ElephantDBTap.class);

    public static enum TapMode {SOURCE, SINK}

    public static class Args implements Serializable {
        //for source and sink
        public List<String> tmpDirs = null;
        public int timeoutMs = 2 * 60 * 60 * 1000; // 2 hours

        //source specific
        public Fields sourceFields = Fields.ALL;
        public Long version = null; //for sourcing

        //sink specific
        public Fields sinkFields = Fields.ALL;
    }

    String domainDir;
    DomainSpec spec;
    Args args;
    String newVersionPath;
    TapMode mode;

    public ElephantDBTap(String dir, DomainSpec spec, Args args, TapMode mode) throws IOException {
        domainDir = dir;
        this.args = args;
        this.spec = new DomainStore(dir, spec).getSpec();
        this.mode = mode;

        setStringPath(domainDir);
        setScheme(new ElephantScheme(this.args.sourceFields,
            this.args.sinkFields, this.spec));
    }

    private transient DomainStore domainStore;

    public DomainStore getDomainStore() throws IOException {
        if (domainStore == null) {
            domainStore = new DomainStore(domainDir, spec);
        }
        return domainStore;
    }

    public DomainSpec getSpec() {
        return spec;
    }

    @Override public void sourceConfInit(FlowProcess<JobConf> process, JobConf conf) {
        super.sourceConfInit( process, conf );

        FileInputFormat.setInputPaths(conf, "/" + UUID.randomUUID().toString());

        ElephantInputFormat.Args eargs = new ElephantInputFormat.Args(domainDir);
        eargs.inputDirHdfs = domainDir;

        if (args.tmpDirs != null) {
            LocalElephantManager.setTmpDirs(conf, args.tmpDirs);
        }

        eargs.version = args.version;

        conf.setInt("mapred.task.timeout", args.timeoutMs);
        Utils.setObject(conf, ElephantInputFormat.ARGS_CONF, eargs);
    }

    @Override public void sinkConfInit(FlowProcess<JobConf> process, JobConf conf) {
        super.sinkConfInit( process, conf );

        ElephantOutputFormat.Args args = null;
        try {
            args = outputArgs(conf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // serialize this particular argument off into the JobConf.
        Utils.setObject(conf, ElephantOutputFormat.ARGS_CONF, args);
        conf.setInt("mapred.task.timeout", this.args.timeoutMs);
        conf.setNumReduceTasks(spec.getNumShards());
        conf.setSpeculativeExecution(false);
    }

    public ElephantOutputFormat.Args outputArgs(JobConf conf) throws IOException {
        DomainStore dstore = getDomainStore();
        FileSystem fs = dstore.getFileSystem();

        if (newVersionPath == null) { //working around cascading calling sinkinit twice
            newVersionPath = dstore.createVersion();
            // make the path qualified before serializing into the jobconf
            newVersionPath = new Path(newVersionPath).makeQualified(fs).toString();
        }
        ElephantOutputFormat.Args eargs = new ElephantOutputFormat.Args(spec, newVersionPath);

        if (args.tmpDirs != null) {
            LocalElephantManager.setTmpDirs(conf, args.tmpDirs);
        }

        return eargs;
    }

    @Override
    public Path getPath() {
        return new Path(domainDir);
    }
    
    @Override
    public long getModifiedTime(JobConf conf) throws IOException {
        DomainStore dstore = getDomainStore();
        return (mode == TapMode.SINK) ? 0 : dstore.mostRecentVersion();
    }

    @Override
    public String getIdentifier() {
        String versionString = "";
        try {
            DomainStore dstore = getDomainStore();
            versionString = ((mode == TapMode.SINK) ? "LATEST" : "" + dstore.mostRecentVersion());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return domainDir + Path.SEPARATOR
            + ((mode == TapMode.SINK) ? "sink" : "source")
            + Path.SEPARATOR + versionString;
    }

    @Override
    public boolean createResource(JobConf conf) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean deleteResource(JobConf conf) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean commitResource(JobConf conf) {
        try {
            DomainStore dstore = getDomainStore();
            dstore.getFileSystem().mkdirs(new Path(newVersionPath));
            
            dstore.succeedVersion(newVersionPath);
            
            return true;

        } catch (IOException e) {
            throw new TapException("Couldn't finalize new elephant domain version", e);
        } finally {
            newVersionPath = null; //working around cascading calling sinkinit twice
        }
    }
    
    @Override
    public boolean rollbackResource(JobConf conf) throws IOException {
        DomainStore dstore = getDomainStore();
        dstore.failVersion(newVersionPath);        
        
        return true;
    }

    public boolean onThrowable(Flow flow, Throwable t) {
        return false;
    }
}
