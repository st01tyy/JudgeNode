package edu.bistu.rubenoj;

import com.google.gson.Gson;
import edu.bistu.rubenoj.judgenode.JudgeNode;
import edu.bistu.rubenoj.judgenode.RunResult;
import edu.bistu.rubenoj.judgenode.config.JudgeNodeConfig;
import edu.bistu.rubenoj.judgenode.config.LanguageConfig;
import edu.bistu.rubenoj.shared.Language;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import shared.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("BusyWait")
@Slf4j
public class Jury implements Runnable
{
    private final BlockingQueue<Submission> submissionQueue;
    private final int juryID;
    private final Map<String, Language> languageMap;
    private final Map<String, LanguageConfig> languageConfigMap;
    private final JudgeNodeConfig config;

    private final String WORK_SPACE;

    public Jury(BlockingQueue<Submission> submissionQueue, int juryID, Map<String, Language> languageMap, Map<String, LanguageConfig> languageConfigMap, JudgeNodeConfig config)
    {
        this.submissionQueue = submissionQueue;
        this.juryID = juryID;
        this.languageMap = languageMap;
        this.languageConfigMap = languageConfigMap;
        this.config = config;
        WORK_SPACE = config.getWorkspace() + "/" + juryID;
    }

    @Override
    public void run()
    {
        log.info("Jury " + juryID +" starts working");

        //create workspace
        log.info("Jury " + juryID + " creating workspace");
        try
        {
            int res = executeCommand("mkdir " + WORK_SPACE);
            if(res != 0)
                throw new RuntimeException("Command exitValue isn't 0");
        }
        catch (RuntimeException | IOException | InterruptedException e)
        {
            log.error("Jury " + juryID + " failed to create workspace");
            e.printStackTrace();
            return;
        }

        //wait for submission
        while(!JudgeNode.shutdown.get())
        {
            Submission submission;
            try
            {
                submission = submissionQueue.poll(1, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                log.error("Jury " + juryID + " encounters exception when waiting for submission");
                e.printStackTrace();
                break;
            }
            if (submission == null)
                continue;

            showJudgeResult(submission, SubmissionResult.Running);

            //store source file
            BufferedOutputStream outputStream;
            try
            {
                outputStream = new BufferedOutputStream(new FileOutputStream(WORK_SPACE + "/" + submission.getSourceFileName()));
                outputStream.write(submission.getArr());
                outputStream.flush();
                outputStream.close();
            }
            catch (IOException e)
            {
                log.error(e.getMessage());
                e.printStackTrace();
                showJudgeResult(submission, SubmissionResult.Judge_Error);
                continue;
            }

            //compile source file
            try
            {
                boolean compile_res = compile(submission);
                if(!compile_res)
                {
                    showJudgeResult(submission, SubmissionResult.Compile_Error);
                    continue;
                }
            }
            catch (Exception e)
            {
                log.error(e.getMessage());
                e.printStackTrace();
                showJudgeResult(submission, SubmissionResult.Judge_Error);
                continue;
            }

            //get problem limit
            Limit limit = getProblemLimit(submission.getProblemID());
            if(limit == null)
            {
                log.error("failed to get limit of problem " + submission.getProblemID());
                showJudgeResult(submission, SubmissionResult.Judge_Error);
                continue;
            }

            //convert execute command
            Language language = languageMap.get(submission.getLanguageName());
            LanguageConfig languageConfig = languageConfigMap.get(submission.getLanguageName());
            String command = language.getExecuteCommand();
            command = replaceParameters(submission, languageConfig, command);

            //download checker
            Checker checker = downloadChecker(submission.getProblemID());
            boolean isCheckerRunnable = (checker == null || languageMap.containsKey(checker.getLanguageName()));
            if(isCheckerRunnable && checker != null)
            {
                try
                {
                    //store checker
                    BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(WORK_SPACE + "/" + checker.getFileName()));
                    os.write(checker.getArr());
                    os.flush();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    showJudgeResult(submission, SubmissionResult.Judge_Error);
                    continue;
                }
                if(!compileChecker(checker))
                {
                    showJudgeResult(submission, SubmissionResult.Judge_Error);
                    continue;
                }
            }

            //download test case
            TestCase[] cases = downloadTestCase(submission.getProblemID());
            if(cases == null || cases.length == 0)
            {
                log.error("failed to download test cases of problem: " + submission.getProblemID());
                showJudgeResult(submission, SubmissionResult.Judge_Error);
                continue;
            }

            int n = 0;
            Double timeCount = 0.0;
            Long maxMemory = 0L;

            List<String> outputList = new ArrayList<>();

            int i;
            for(i = 0; i < cases.length; i++)
            {
                try
                {
                    storeTestCase(cases[i]);
                    log.info("case[" + i + "]" + cases[i].getInput().length);
                    RunResult result= runexec(command, limit);
                    if(result.getCpuTime() != null)
                    {
                        log.info("cputime: " + result.getCpuTime());
                        timeCount += result.getCpuTime();
                        n++;
                    }
                    if(result.getMemory() != null && result.getMemory() != 0 && result.getMemory() > maxMemory)
                        maxMemory = result.getMemory();
                    int res = result.getResult();
                    if(res != 0)
                    {
                        if(res == -1)
                            showJudgeResult(submission, SubmissionResult.Judge_Error);
                        else if(res == 1)
                            showJudgeResult(new JudgeResult(n, timeCount, maxMemory), submission, SubmissionResult.Time_Limit);
                        else if(res == 2)
                            showJudgeResult(new JudgeResult(n, timeCount, maxMemory), submission, SubmissionResult.Memory_Limit);
                        break;
                    }

                    String output = getOutput();
                    if(!isCheckerRunnable)
                        outputList.add(output);
                    else
                    {
                        if(!judge(checker))
                        {
                            showJudgeResult(new JudgeResult(n, timeCount, maxMemory), submission, SubmissionResult.Wrong_Answer);
                            break;
                        }
                    }
                }
                catch (IOException | InterruptedException e)
                {
                    log.error(e.getMessage());
                    e.printStackTrace();
                    showJudgeResult(submission, SubmissionResult.Judge_Error);
                    break;
                }
            }
            if(i == cases.length)
            {
                if(isCheckerRunnable)
                    showJudgeResult(new JudgeResult(n, timeCount, maxMemory), submission, SubmissionResult.Accepted);
                else
                {
                    //send results to others
                }
            }
        }

        //clear work space
        try
        {
            executeCommand("rm -r " + WORK_SPACE);
        }
        catch (IOException | InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @NotNull
    private String replaceParameters(Submission submission, LanguageConfig languageConfig, String command)
    {
        if(command.contains("${app1}"))
            command = command.replace("${app1}", languageConfig.getApp1());
        if(command.contains("${app2}"))
            command = command.replace("${app2}", languageConfig.getApp2());
        if(command.contains("${workspace}"))
            command = command.replace("${workspace}", WORK_SPACE);
        if(command.contains("${source_file}"))
            command = command.replace("${source_file}", submission.getSourceFileName());
        if(command.contains("${source}"))
            command = command.replace("${source}", submission.getSourceName());
        return command;
    }

    private String replaceParameters(Checker checker, LanguageConfig languageConfig, String command)
    {
        if(command.contains("${app1}"))
            command = command.replace("${app1}", languageConfig.getApp1());
        if(command.contains("${app2}"))
            command = command.replace("${app2}", languageConfig.getApp2());
        if(command.contains("${workspace}"))
            command = command.replace("${workspace}", WORK_SPACE);
        if(command.contains("${source_file}"))
            command = command.replace("${source_file}", checker.getFileName());
        if(command.contains("${source}"))
            command = command.replace("${source}", getFileNameWithoutPostFix(checker.getFileName()));
        return command;
    }

    private String getFileNameWithoutPostFix(String name)
    {
        int i;
        for(i = name.length() - 1; i > 0; i--)
        {
            if(name.charAt(i) == '.')
                break;
        }
        if(i == 0)
            return name;
        return name.substring(0, i);
    }

    private RunResult runexec(String executeCommand, Limit limit) throws IOException, InterruptedException
    {
        RunResult result = new RunResult();
        String command  = "runexec --timelimit " + limit.getTimeLimit() + " --memlimit " +
                (long) (limit.getMemoryLimit() * Math.pow(2, 10)) +
                ((JudgeNode.windows) ? " --read-only-dir /" : "" )+
                " --input " +
                WORK_SPACE +
                "/case_input.txt --output " +
                WORK_SPACE +
                "/run_result.txt -- " + executeCommand;
        log.info("executing command: " + command);
        Process process = Runtime.getRuntime().exec(command);
        while(process.isAlive()){Thread.sleep(500);}
        log.info("judge process exit value: " + process.exitValue());
        if(process.exitValue() != 0)
        {
            result.setResult(-1);
            return result;
        }

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Map<String, String> map = readRunexecResult(bufferedReader);
        String str;
        str = map.get("cputime");
        str = str.substring(0, str.length() - 1);
        result.setCpuTime(Double.valueOf(str));
        str = map.get("memory");
        str = str.substring(0, str.length() - 1);
        result.setMemory(Long.valueOf(str));
        if(map.containsKey("terminationreason"))
        {
            if(map.get("terminationreason").equals("cputime"))
                result.setResult(1);
            else
                result.setResult(2);
            return result;
        }
        result.setResult(0);
        return result;
    }

    private void showJudgeResult(JudgeResult judgeResult, Submission submission, SubmissionResult result)
    {
        log.info("Jury " + juryID + " has a judge result " + (System.currentTimeMillis() - submission.getSubmitTime()) + "ms after user submit, " + submission.getSubmissionID() + " " + result);
        if(judgeResult == null)
            judgeResult = new JudgeResult();
        judgeResult.setSubmissionID(submission.getSubmissionID());
        judgeResult.setResult(result);
        Gson gson = new Gson();
        String json = gson.toJson(judgeResult);
        log.info(json);
        OkHttpClient okHttpClient = new OkHttpClient();
        RequestBody requestBody = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().post(requestBody).url("http://" + config.getWebServer() + "/api/update_result").build();
        try
        {
            Response response = okHttpClient.newCall(request).execute();
            log.info("update submission result: " + response.code());
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private void showJudgeResult(Submission submission, SubmissionResult result)
    {
        showJudgeResult(null, submission, result);
    }

    private boolean compile(Submission submission) throws Exception
    {
        Language language = languageMap.get(submission.getLanguageName());
        LanguageConfig languageConfig = languageConfigMap.get(submission.getLanguageName());
        if(language == null || languageConfig == null)
            throw new Exception("unsupported language: " + submission.getLanguageName());
        String command = language.getCompileCommand();
        if(command == null || command.length() == 0)
        {
            log.info("current language: " + language.getLanguageName() + "doesn't have compile command");
            return true;
        }
        command = replaceParameters(submission, languageConfig, command);
        return executeCommand(command) == 0;
    }

    private boolean compileChecker(Checker checker)
    {
        String compileCommand = replaceParameters(checker, languageConfigMap.get(checker.getLanguageName()), languageMap.get(checker.getLanguageName()).getCompileCommand());
        log.info("checker compile command: " + compileCommand);
        try
        {
            Process process = Runtime.getRuntime().exec(compileCommand);
            while(process.isAlive())
            {
                Thread.sleep(500);
            }
            if(process.exitValue() != 0)
            {
                String errorMessage = new String(process.getErrorStream().readAllBytes());
                log.error("checker compile failed\n" + errorMessage);
            }
            return process.exitValue() == 0;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("BusyWait")
    private int executeCommand(String command) throws IOException, InterruptedException
    {
        log.info("Jury " + juryID + " executing command: " + command);
        Process process = Runtime.getRuntime().exec(command);
        while(process.isAlive()){Thread.sleep(1000);}

        int res = process.exitValue();
        if(res != 0)
        {
            InputStreamReader inputStreamReader = new InputStreamReader(process.getErrorStream());
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String str = bufferedReader.readLine();
            while(str != null)
            {
                System.out.println(str);
                str = bufferedReader.readLine();
            }
            bufferedReader.close();
            inputStreamReader.close();
        }
        return process.exitValue();
    }

    private Limit getProblemLimit(Long problemID)
    {
        Request request = new Request.Builder()
                .url("http://" + config.getWebServer() + "/api/problem_limit?problemID=" + problemID)
                .get()
                .build();
        OkHttpClient okHttpClient = new OkHttpClient();
        try
        {
            Response response = okHttpClient.newCall(request).execute();
            Gson gson = new Gson();
            //noinspection ConstantConditions
            return gson.fromJson(response.body().string(), Limit.class);
        }
        catch (Exception e)
        {
            log.error(e.getMessage());
            return null;
        }
    }

    private TestCase[] downloadTestCase(Long problemID)
    {
        Request request = new Request.Builder()
                .url("http://" + config.getWebServer() + "/api/download_test_case?problemID=" + problemID)
                .get()
                .build();
        OkHttpClient okHttpClient = new OkHttpClient();
        try
        {
            Response response = okHttpClient.newCall(request).execute();
            log.info("download test case response code: " + response.code());
            if(response.code() != 200)
                return null;
            @SuppressWarnings("ConstantConditions") ObjectInputStream inputStream = new ObjectInputStream(new BufferedInputStream(response.body().byteStream()));
            Object object = inputStream.readObject();
            TestCase[] cases = (TestCase[]) object;
            log.info(String.valueOf(cases == null));
            if(cases == null || cases.length == 0)
                return null;
            inputStream.close();
            return cases;
        }
        catch (IOException | ClassNotFoundException e)
        {
            log.error(e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void storeTestCase(TestCase testCase) throws IOException
    {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(testCase.getInput());
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(WORK_SPACE + "/case_input.txt"));
        outputStream.write(inputStream.readAllBytes());
        outputStream.flush();
        inputStream.close();
        outputStream.close();

        inputStream = new ByteArrayInputStream(testCase.getOutput());
        outputStream = new BufferedOutputStream(new FileOutputStream(WORK_SPACE + "/case_output.txt"));
        outputStream.write(inputStream.readAllBytes());
        outputStream.flush();
        inputStream.close();
        outputStream.close();
    }

    private String getOutput() throws IOException
    {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(WORK_SPACE + "/run_result.txt")));
        for(int i = 0; i < 6; i++)
        {
            bufferedReader.readLine();
        }
        StringBuilder sb = new StringBuilder();
        char[] arr = new char[1024];
        int length = bufferedReader.read(arr);
        while(length > 0)
        {
            sb.append(arr, 0, length);
            length = bufferedReader.read(arr);
        }
        return sb.toString();
    }

    private String function(BufferedReader bufferedReader) throws IOException
    {
        StringBuilder resultBuilder = new StringBuilder();
        String str = bufferedReader.readLine();
        while(str != null)
        {
            str = removeTrailingZeroes(str);
            if(str.length() > 0)
                resultBuilder.append(str).append('\n');
            str = bufferedReader.readLine();
        }
        bufferedReader.close();
        return resultBuilder.substring(0, resultBuilder.length() - 1);
    }

    private boolean judge(Checker checker) throws IOException, InterruptedException
    {
        String result = getOutput();
        if(checker != null)
        {
            BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(WORK_SPACE + "/output.txt"));
            os.write(result.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            return runChecker(checker);
        }
        return result.equals(function(new BufferedReader(new InputStreamReader(new FileInputStream(WORK_SPACE + "/case_output.txt")))));
    }

    private boolean runChecker(Checker checker) throws InterruptedException, IOException
    {
        String executeCommand = replaceParameters(checker, languageConfigMap.get(checker.getLanguageName()), languageMap.get(checker.getLanguageName()).getExecuteCommand());
        String str = "runexec --timelimit 5 --memlimit 67108864" + ((JudgeNode.windows) ? " --read-only-dir /" : " --read-only-dir " + WORK_SPACE)
                + " --dir "
                + WORK_SPACE + " -- "
                + executeCommand;
        log.info("running checker: " + str);
        Process process = Runtime.getRuntime().exec(str);
        while(process.isAlive())
        {
            Thread.sleep(500);
        }
        if(process.exitValue() != 0)
            throw new InterruptedException();
        String error = "no error";
        error = new String((process.getErrorStream() != null) ? process.getErrorStream().readAllBytes(): error.getBytes(StandardCharsets.UTF_8));
        log.info(error);
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Map<String, String> map = readRunexecResult(bufferedReader);
        int val = Integer.parseInt(map.get("returnvalue"));
        if(val != 0 && val != 1)
            throw new InterruptedException();
        return val == 0;
    }

    private Map<String, String> readRunexecResult(BufferedReader bufferedReader) throws IOException
    {
        String str = bufferedReader.readLine();
        Map<String, String> map = new HashMap<>();
        while(str != null)
        {
            log.info(str);
            String[] arr = str.split("=");
            map.put(arr[0], arr[1]);
            str = bufferedReader.readLine();
        }
        bufferedReader.close();
        return map;
    }

    private String removeTrailingZeroes(String s)
    {
        int index;
        for (index = s.length() - 1; index >= 0; index--)
        {
            if (s.charAt(index) != 0 && s.charAt(index) != ' ')
            {
                break;
            }
        }
        return s.substring(0, index + 1);
    }

    private Checker downloadChecker(Long problemID)
    {
        Request request = new Request.Builder()
                .url("http://" + config.getWebServer() + "/api/download_checker?problemID=" + problemID)
                .get()
                .build();
        OkHttpClient okHttpClient = new OkHttpClient();
        try
        {
            Response response = okHttpClient.newCall(request).execute();
            log.info("download checker response code: " + response.code());
            if(response.code() != 200)
                return null;
            @SuppressWarnings("ConstantConditions") ObjectInputStream inputStream = new ObjectInputStream(new BufferedInputStream(response.body().byteStream()));
            Object object = inputStream.readObject();
            Checker checker = (Checker) object;
            inputStream.close();
            return checker;
        }
        catch (IOException | ClassNotFoundException e)
        {
            log.error(e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
