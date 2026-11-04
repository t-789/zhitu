package org.example.lifecomposer.importer;

import org.example.lifecomposer.LifeComposerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Standalone data import CLI. Boots the Spring context without a web server,
 * sets {@code lifecomposer.import.mode=true} (so the default admin bootstrap is
 * skipped), runs the requested importers and exits with a status code.
 *
 * <pre>
 * ./mvnw -q spring-boot:run \
 *   -Dspring-boot.run.main-class=org.example.lifecomposer.importer.DataImportCli \
 *   -Dspring-boot.run.arguments="--import-dir=../样例 --report=target/import-report.json"
 * </pre>
 */
public final class DataImportCli {

    private DataImportCli() {
    }

    public static void main(String[] args) {
        if (hasHelpFlag(args)) {
            printUsage();
            System.exit(0);
        }

        LifeComposerApplication.useUtcStorageZone();
        SpringApplication application = new SpringApplication(LifeComposerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("lifecomposer.import.mode", "true"));

        int exitCode;
        try (ConfigurableApplicationContext context = application.run(args)) {
            ImportOptions options = ImportOptions.parse(args);
            if (!options.hasImportTarget()) {
                System.err.println("缺少 --import-dir 或具体导入路径参数。使用 --help 查看用法。");
                exitCode = 2;
            } else {
                DataImportService service = context.getBean(DataImportService.class);
                ImportReport report = service.run(options);
                String json = report.toPrettyJson();
                System.out.println(json);
                writeReportIfNeeded(options.getReportPath(), json);
                exitCode = report.hasErrors() ? 1 : 0;
            }
        } catch (Exception e) {
            System.err.println("导入失败: " + e.getMessage());
            exitCode = 2;
        }
        System.exit(exitCode);
    }

    private static boolean hasHelpFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void writeReportIfNeeded(Path reportPath, String json) {
        if (reportPath == null) {
            return;
        }
        try {
            Path parent = reportPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportPath, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("无法写入导入报告 " + reportPath + ": " + e.getMessage());
        }
    }

    private static void printUsage() {
        System.out.println("""
                LifeComposer 数据导入工具（独立 CLI，不启动 Web 服务）

                用法:
                  ./import.sh --import-dir=../样例 [选项]

                选项:
                  --import-dir <dir>        样例包根目录（默认导入其下全部数据类型）
                  --resources               仅导入 data/resources.json
                  --profiles                仅导入 data/student_profiles.json
                  --rag-chunks              仅导入 rag/chunks.json
                  --capability-tags         仅导入 draft/capability-tags.json
                  --credit-rules-dir <dir>  仅导入 extracted/*.json
                  --rebuild-embeddings      强制重新生成全部 RAG embedding
                  --dry-run                 只校验，不写数据库
                  --report <file>           将 JSON 报告写入文件
                  --demo-password <pwd>     演示账号密码（默认 testuser）
                  --help                    显示本帮助

                退出码:
                  0  全部成功（含 dry-run）
                  1  存在记录/文件/embedding 错误
                  2  参数错误或启动异常
                """);
    }
}
