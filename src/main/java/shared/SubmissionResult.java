package shared;

public enum SubmissionResult
{
    Waiting("Waiting"), Running("Running"), Compile_Error("Compile Error"),
    Runtime_Error("Runtime Error"), Time_Limit("Time Limit Exceeded"),
    Memory_Limit("Memory Limit Exceeded"), Wrong_Answer("Wrong Answer"), Accepted("Accepted"),
    Expired("Expired"), Judge_Error("Judge Error");

    private String resultName;

    SubmissionResult(String resultName)
    {
        this.resultName = resultName;
    }

    public String getResultName()
    {
        return resultName;
    }
}
