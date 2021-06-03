package edu.bistu.rubenoj.judgenode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.gson.Gson;
import edu.bistu.rubenoj.Jury;
import edu.bistu.rubenoj.judgenode.config.JudgeNodeConfig;
import edu.bistu.rubenoj.judgenode.config.LanguageConfig;
import edu.bistu.rubenoj.shared.Language;
import edu.bistu.rubenoj.shared.LanguageVerifyRequest;
import edu.bistu.rubenoj.shared.LanguageVerifyResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import shared.Submission;
import sun.misc.Signal;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"SameParameterValue", "SingleStatementInBlock"})
@Slf4j
public class JudgeNode
{
    public static AtomicBoolean shutdown;

    public static volatile boolean windows = false;

    static {shutdown = new AtomicBoolean(false);}

    @SuppressWarnings("BusyWait")
    public static void main(String[] args) throws InterruptedException
    {
        MySignalHandler signalHandler = new MySignalHandler();
        Signal.handle(new Signal("INT"), signalHandler);
        Signal.handle(new Signal("TERM"), signalHandler);

        if(args.length == 2 && args[0].equals("--windows") && args[1].equals("true"))
            windows = true;

        log.info("windows: " + windows);

        System.out.println("reading config");
        //JudgeNodeConfig config = readConfig("config.xml");
        JudgeNodeConfig config = readConfig("/root/JudgeNode/config.xml");
        if(config == null)
        {
            System.out.println("failed to read config");
            return;
        }
        System.out.println("web server: " + config.getWebServer());
        System.out.println("kafka server: " + config.getKafkaServer());
        System.out.println("work thread number: " + config.getWorkThread());
        System.out.println("workspace: " + config.getWorkspace());
        if(config.getLanguageConfigList() == null || config.getLanguageConfigList().size() == 0)
        {
            System.out.println("could not found language config, should config at least one language");
            return;
        }
        System.out.println("config language number: " + config.getLanguageConfigList().size());
        System.out.println("verifying languages");
        Map<String, LanguageConfig> languageConfigMap = new HashMap<>();
        for(LanguageConfig languageConfig : config.getLanguageConfigList())
        {
            languageConfigMap.put(languageConfig.getName(), languageConfig);
        }
        List<Language> languageList = verifyLanguages(config.getLanguageConfigList(), config.getWebServer());
        if(languageList == null || languageList.size() == 0)
        {
            System.out.println("no valid language config");
            return;
        }
        StringBuilder sb = new StringBuilder("verify passed languages: ");
        for(Language language : languageList)
        {
            sb.append(language.getLanguageName()).append(" ");
            sb.append(language.getTopicName()).append(", ");
        }
        sb.delete(sb.length() - 2, sb.length());
        System.out.println(sb.toString());

        Map<String, Language> languageMap = new HashMap<>();
        List<String> topics = new ArrayList<>();
        for(Language language : languageList)
        {
            topics.add(language.getTopicName());
            languageMap.put(language.getLanguageName(), language);
        }

        for(LanguageConfig languageConfig : config.getLanguageConfigList())
        {
            if(!languageMap.containsKey(languageConfig.getName()))
                languageConfigMap.remove(languageConfig.getName());
        }

        BlockingQueue<Submission> queue = new LinkedBlockingQueue<>();
        int n = config.getWorkThread();
        ExecutorService threadPool = Executors.newFixedThreadPool(n);

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", config.getKafkaServer());
        props.setProperty("group.id", "judge");
        props.setProperty("enable.auto.commit", "false");
        props.setProperty("auto.commit.interval.ms", "1000");
        props.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty("value.deserializer", "edu.bistu.rubenoj.judgenode.MyDeserializer");
        props.setProperty("auto.offset.reset", "earliest");
        props.setProperty("max.poll.records", String.valueOf(Math.max(n / 2, 1)));
        KafkaConsumer<String, Submission> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(topics);
        for(int i = 0; i < n; i++)
        {
            threadPool.execute(new Jury(queue, i, languageMap, languageConfigMap, config));
        }
        while (!shutdown.get())
        {
            ConsumerRecords<String, Submission> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, Submission> record : records)
            {
                log.info("submission received, id = " + record.value().getSubmissionID());
                queue.add(record.value());
            }
            while(queue.size() > n / 2)
            {
                Thread.sleep(500);
            }
            consumer.commitSync();
        }

        log.info("shutdown");
        threadPool.shutdown();
        consumer.close();
    }

    private static JudgeNodeConfig readConfig(String configFilePath)
    {
        ObjectMapper mapper = new XmlMapper();
        try
        {
            return mapper.readValue(new File(configFilePath), JudgeNodeConfig.class);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    private static List<Language> verifyLanguages(List<LanguageConfig> languageConfigList, String webServer)
    {
        List<String> languageNameList = new ArrayList<>(languageConfigList.size());
        for(LanguageConfig config : languageConfigList)
        {
            languageNameList.add(config.getName());
        }
        LanguageVerifyRequest languageVerifyRequest = new LanguageVerifyRequest(languageNameList);
        Gson gson = new Gson();
        String json = gson.toJson(languageVerifyRequest);
        OkHttpClient okHttpClient = new OkHttpClient();
        RequestBody requestBody = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().post(requestBody).url("http://" + webServer + "/api/verify_languages").build();
        try
        {
            Response response = okHttpClient.newCall(request).execute();
            log.info(String.valueOf(response.code()));
            if(response.code() != 200)
                return null;
            LanguageVerifyResult result = gson.fromJson(Objects.requireNonNull(response.body()).string(), LanguageVerifyResult.class);
            return result.getLanguageList();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return null;
        }
    }


}
class MySignalHandler implements sun.misc.SignalHandler
{

    @Override
    public void handle(Signal signal)
    {
        JudgeNode.shutdown.compareAndSet(false, true);
    }
}
