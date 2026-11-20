using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace StemWise.Qualification {
    public sealed class CommandResult {
        public int ExitCode;
        public string Stdout;
        public string Stderr;
        public bool TimedOut;
        public bool OutputTruncated;
        public string OutputLog;
    }

    // Start suspended so even an immediately escaping descendant belongs to our job.
    public static class OwnedProcess {
        [StructLayout(LayoutKind.Sequential)] struct Security {
            public int size; public IntPtr descriptor; public int inherit;
        }
        [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct Startup {
            public int size; public string reserved, desktop, title;
            public int x,y,width,height,xchars,ychars,fill,flags;
            public short show, reservedSize; public IntPtr reservedBytes, stdin, stdout, stderr;
        }
        [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
            public IntPtr process, thread; public int processId, threadId;
        }
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool CreatePipe(out IntPtr read, out IntPtr write, ref Security security, int size);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetHandleInformation(IntPtr handle, int mask, int flags);
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr security, string name);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int kind, IntPtr info, uint length);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job, int kind, IntPtr info, uint length, IntPtr returned);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr job, uint code);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process, uint code);
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcess(string application, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inherit, uint flags, IntPtr environment, string cwd, ref Startup startup, out ProcessInfo process);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
        [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetProcessTimes(IntPtr process, out ulong birth, out ulong exit, out ulong kernel, out ulong user);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

        static void Require(bool success) { if (!success) throw new Win32Exception(Marshal.GetLastWin32Error()); }
        static void Close(ref IntPtr handle) { if (handle != IntPtr.Zero) { CloseHandle(handle); handle = IntPtr.Zero; } }
        public static ulong Birth(int pid) {
            IntPtr process=OpenProcess(0x1000,false,pid);
            if (process == IntPtr.Zero) { int code=Marshal.GetLastWin32Error(); if (code==87 || code==1168) return 0; throw new Win32Exception(code); }
            try { ulong birth,exit,kernel,user; Require(GetProcessTimes(process,out birth,out exit,out kernel,out user)); return birth; }
            finally { Close(ref process); }
        }
        public static bool TerminateExact(int pid, ulong expectedBirth) {
            if (expectedBirth == 0) return false;
            IntPtr process=OpenProcess(0x101001,false,pid);
            if (process == IntPtr.Zero) { int code=Marshal.GetLastWin32Error(); if (code==87 || code==1168) return false; throw new Win32Exception(code); }
            try {
                ulong birth,exit,kernel,user; Require(GetProcessTimes(process,out birth,out exit,out kernel,out user));
                if (birth != expectedBirth) return false;
                Require(TerminateProcess(process,1));
                if (WaitForSingleObject(process,5000) != 0) throw new IOException("Exact fixture process retirement failed");
                return true;
            } finally { Close(ref process); }
        }
        public static string Quote(string value) {
            if (value == null) throw new ArgumentNullException("value");
            if (value.Length > 0 && value.IndexOfAny(new char[] {' ','\t','\r','\n','"','&','|','<','>','^','(',')','%'}) < 0)
                return value;
            var quoted = new StringBuilder("\"");
            int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                quoted.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
                quoted.Append(c); slashes = 0;
            }
            return quoted.Append('\\', slashes * 2).Append('"').ToString();
        }

        // Drain continuously after the limit: stopping reads would deadlock a verbose child.
        sealed class Capture {
            const int Capacity = 1048576;
            readonly StringBuilder prefix = new StringBuilder();
            readonly char[] tail;
            readonly TextWriter log;
            readonly string label;
            int next, length;
            public bool Truncated;
            public Capture(TextWriter log, string label) {
                this.log=log; this.label=label;
                if (log!=null) tail=new char[Capacity];
            }
            public string Text {
                get {
                    if (tail==null) return prefix.ToString();
                    if (length<Capacity) return new string(tail,0,length);
                    return new string(tail,next,Capacity-next)+new string(tail,0,next);
                }
            }
            public void Drain(StreamReader reader) {
                var buffer = new char[8192];
                int count;
                while ((count=reader.Read(buffer,0,buffer.Length)) != 0) {
                    if (log==null) {
                        int retained = Math.Min(count,Capacity-prefix.Length);
                        if (retained != 0) prefix.Append(buffer,0,retained);
                        if (retained != count) Truncated=true;
                    } else {
                        lock (log) { log.Write(label); log.Write(buffer,0,count); log.Flush(); }
                        int first=Math.Min(count,Capacity-next);
                        Array.Copy(buffer,0,tail,next,first);
                        Array.Copy(buffer,first,tail,0,count-first);
                        next=(next+count)%Capacity;
                        if (length+count>Capacity) Truncated=true;
                        length=Math.Min(Capacity,length+count);
                    }
                }
            }
        }
        sealed class PipeReadStream : FileStream {
            public PipeReadStream(SafeFileHandle handle) : base(handle,FileAccess.Read) {}
            public override int Read(byte[] buffer, int offset, int count) {
                // Short reads let StreamReader publish flushed text without filling its caller's buffer.
                return base.Read(buffer,offset,count > 1 ? count-1 : count);
            }
        }
        static void RetireJob(IntPtr job, IntPtr process, uint code) {
            Require(TerminateJobObject(job,code));
            uint waited=WaitForSingleObject(process,5000);
            if (waited != 0) throw new IOException("Owned process retirement failed: " + waited);
            IntPtr info=Marshal.AllocHGlobal(48);
            try {
                var deadline=Stopwatch.StartNew();
                do {
                    Require(QueryInformationJobObject(job,1,info,48,IntPtr.Zero));
                    if (Marshal.ReadInt32(info,40)==0) return;
                    System.Threading.Thread.Sleep(10);
                } while (deadline.ElapsedMilliseconds < 5000);
                throw new IOException("Owned descendant retirement failed");
            } finally { Marshal.FreeHGlobal(info); }
        }

        public static CommandResult Run(string executable, string[] arguments, string cwd, int timeoutSeconds, string outputLog=null, string standardInput=null) {
            if (timeoutSeconds <= 0 || timeoutSeconds > 86400) throw new ArgumentOutOfRangeException("timeoutSeconds");
            byte[] inputBytes=Encoding.UTF8.GetBytes(standardInput ?? "");
            if (inputBytes.Length>4096) throw new ArgumentException("Owned process stdin exceeds its bounded pipe capacity");
            if (String.IsNullOrEmpty(outputLog)) outputLog=null;
            IntPtr job=IntPtr.Zero, outputRead=IntPtr.Zero, outputWrite=IntPtr.Zero,
                   errorRead=IntPtr.Zero, errorWrite=IntPtr.Zero, inputRead=IntPtr.Zero, inputWrite=IntPtr.Zero;
            var child = new ProcessInfo();
            StreamReader output=null, error=null;
            bool retired = false, assigned = false;
            TextWriter log=null;
            if (outputLog!=null) {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputLog)));
                log=new StreamWriter(new FileStream(outputLog,FileMode.Append,FileAccess.Write,FileShare.ReadWrite),new UTF8Encoding(false));
            }
            var stdoutCapture=new Capture(log,"[stdout] "); var stderrCapture=new Capture(log,"[stderr] ");
            Task stdout=null, stderr=null;
            Exception failure=null;
            try {
                job = CreateJobObject(IntPtr.Zero, null);
                Require(job != IntPtr.Zero);
                int length = IntPtr.Size == 8 ? 144 : 112;
                IntPtr info = Marshal.AllocHGlobal(length);
                try {
                    Marshal.Copy(new byte[length],0,info,length);
                    Marshal.WriteInt32(info,16,0x2000); // KILL_ON_JOB_CLOSE; no breakaway.
                    Require(SetInformationJobObject(job,9,info,(uint)length));
                } finally { Marshal.FreeHGlobal(info); }
                var security = new Security { size=Marshal.SizeOf(typeof(Security)), inherit=1 };
                Require(CreatePipe(out outputRead,out outputWrite,ref security,0));
                Require(CreatePipe(out errorRead,out errorWrite,ref security,0));
                Require(CreatePipe(out inputRead,out inputWrite,ref security,4096));
                Require(SetHandleInformation(outputRead,1,0));
                Require(SetHandleInformation(errorRead,1,0));
                Require(SetHandleInformation(inputWrite,1,0));
                var startup = new Startup { size=Marshal.SizeOf(typeof(Startup)), flags=0x100,
                                            stdin=inputRead, stdout=outputWrite, stderr=errorWrite };
                var command = new StringBuilder(Quote(executable));
                bool shell = String.Equals(Path.GetFileName(executable),"cmd.exe",StringComparison.OrdinalIgnoreCase);
                for (int index=0; index<arguments.Length; index++) {
                    // cmd /c consumes one command string, rather than a CRT argument.
                    command.Append(' ').Append(shell ? arguments[index] : Quote(arguments[index]));
                }
                Require(CreateProcess(executable,command,IntPtr.Zero,IntPtr.Zero,true,0x08000004,
                                      IntPtr.Zero,cwd,ref startup,out child));
                Require(AssignProcessToJobObject(job,child.process));
                assigned=true;
                Close(ref outputWrite); Close(ref errorWrite); Close(ref inputRead);
                output = new StreamReader(new PipeReadStream(new SafeFileHandle(outputRead,true)),Encoding.UTF8,true);
                outputRead=IntPtr.Zero;
                error = new StreamReader(new PipeReadStream(new SafeFileHandle(errorRead,true)),Encoding.UTF8,true);
                errorRead=IntPtr.Zero;
                stdout=Task.Factory.StartNew(() => stdoutCapture.Drain(output),TaskCreationOptions.LongRunning);
                stderr=Task.Factory.StartNew(() => stderrCapture.Drain(error),TaskCreationOptions.LongRunning);
                if (ResumeThread(child.thread) == 0xffffffff) throw new Win32Exception(Marshal.GetLastWin32Error());
                using (var input=new FileStream(new SafeFileHandle(inputWrite,true),FileAccess.Write)) {
                    inputWrite=IntPtr.Zero;
                    input.Write(inputBytes,0,inputBytes.Length);
                }
                Array.Clear(inputBytes,0,inputBytes.Length);
                uint waited = WaitForSingleObject(child.process,(uint)timeoutSeconds*1000);
                if (waited != 0 && waited != 258) throw new Win32Exception(Marshal.GetLastWin32Error());
                bool timedOut = waited == 258;
                // Retire descendants even after a successful direct-child exit.
                RetireJob(job,child.process,timedOut ? 124u : 0u);
                retired = true;
                uint exitCode; Require(GetExitCodeProcess(child.process,out exitCode));
                if (!Task.WaitAll(new Task[] { stdout, stderr },5000)) throw new IOException("Owned output pipe retirement failed");
                return new CommandResult { ExitCode=(int)exitCode, Stdout=stdoutCapture.Text.ToString(),
                                           Stderr=stderrCapture.Text.ToString(), TimedOut=timedOut,
                                           OutputTruncated=stdoutCapture.Truncated || stderrCapture.Truncated,
                                           OutputLog=outputLog };
            } catch (Exception errorCause) { failure=errorCause; throw; } finally {
                Exception cleanupFailure=null;
                if (!retired && child.process != IntPtr.Zero) {
                    try {
                        if (assigned) RetireJob(job,child.process,125);
                        else { Require(TerminateProcess(child.process,125));
                            if (WaitForSingleObject(child.process,5000)!=0) throw new IOException("Suspended child retirement failed"); }
                        if (stdout!=null && stderr!=null && !Task.WaitAll(new Task[] { stdout,stderr },5000))
                            throw new IOException("Owned output pipe retirement failed");
                    } catch (Exception cleanupCause) { cleanupFailure=cleanupCause; }
                }
                Close(ref job); Close(ref child.thread); Close(ref child.process);
                Close(ref outputWrite); Close(ref errorWrite); Close(ref outputRead); Close(ref errorRead);
                Close(ref inputRead); Close(ref inputWrite);
                Array.Clear(inputBytes,0,inputBytes.Length);
                bool outputRetired = stdout==null || stdout.IsCompleted;
                bool errorRetired = stderr==null || stderr.IsCompleted;
                Action disposeOutput = () => {
                    try { if (output != null) output.Dispose(); }
                    finally {
                        try { if (error != null) error.Dispose(); }
                        finally { if (log != null) log.Dispose(); }
                    }
                };
                if (outputRetired && errorRetired) disposeOutput();
                else {
                    if (cleanupFailure==null) cleanupFailure=new IOException("Owned output pipe retirement failed");
                    // Live readers own their streams and log until they actually retire.
                    Task.WhenAll(stdout ?? Task.FromResult(0), stderr ?? Task.FromResult(0))
                        .ContinueWith(completed => disposeOutput());
                }
                string capturedOutput = outputRetired ? stdoutCapture.Text : "Owned stdout reader has not retired; retained log may contain partial output.";
                string capturedError = errorRetired ? stderrCapture.Text : "Owned stderr reader has not retired; retained log may contain partial output.";
                if (failure!=null) {
                    failure.Data["stdout"]=capturedOutput;
                    failure.Data["stderr"]=capturedError;
                    failure.Data["failureKind"]=cleanupFailure==null ? "infrastructure" : "cleanup";
                    if (cleanupFailure!=null) failure.Data["cleanupFailure"]=cleanupFailure.Message;
                }
                if (cleanupFailure!=null) {
                    var cleanup=new IOException("Owned process cleanup failed",cleanupFailure);
                    cleanup.Data["failureKind"]="cleanup";
                    cleanup.Data["stdout"]=capturedOutput; cleanup.Data["stderr"]=capturedError;
                    if (failure!=null) cleanup.Data["operationFailure"]=failure.Message;
                    throw cleanup;
                }
            }
        }
    }
}
