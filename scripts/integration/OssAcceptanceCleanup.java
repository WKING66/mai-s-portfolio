import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import java.nio.file.*;

/** 只回收真实验收创建的明确对象清单；复用项目 OSS SDK，不扫描或修改 Bucket。 */
class OssAcceptanceCleanup {
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("BASELINE_ISOLATED"))) throw new IllegalStateException("isolated confirmation required");
        var keys = Files.readAllLines(Path.of(args[0]));
        for (var key : keys) {
            if (!key.matches("assets/(avatars|images|attachments)/[a-f0-9-]{36}\\.(png|jpg|jpeg|bin)"))
                throw new IllegalArgumentException("unexpected acceptance object key");
        }
        var config = new ClientBuilderConfiguration();
        config.setConnectionTimeout(5000);
        config.setSocketTimeout(5000);
        config.setMaxErrorRetry(1);
        String proxy = System.getenv("ACCEPTANCE_PROXY");
        if (proxy != null && !proxy.isBlank()) {
            var uri = java.net.URI.create(proxy);
            config.setProxyHost(uri.getHost()); config.setProxyPort(uri.getPort());
        }
        var client = new OSSClientBuilder().build(System.getenv("OSS_ENDPOINT"), System.getenv("OSS_ACCESS_KEY_ID"), System.getenv("OSS_ACCESS_KEY_SECRET"), config);
        try {
            String bucket = System.getenv("OSS_BUCKET_NAME");
            for (var key : keys) {
                if (args.length < 2 || !"--assert-absent".equals(args[1])) client.deleteObject(bucket, key);
                if (client.doesObjectExist(bucket, key)) throw new IllegalStateException("test object remains");
            }
            System.out.println("exact test objects absent: " + keys.size());
        } finally { client.shutdown(); }
    }
}
