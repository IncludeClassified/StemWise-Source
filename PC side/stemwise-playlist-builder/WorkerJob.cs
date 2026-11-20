using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
public static class StemWiseWorkerJob {
    public const int LogLimitBytes = 1024 * 1024;
    public static int WorkerProcessId { get; private set; }

    public static void AppendTail(string path, byte[] data, int count) {
        using (FileStream file = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.Read)) {
            if (file.Length + count > LogLimitBytes) {
                int keep = (int)Math.Min(file.Length, LogLimitBytes / 2);
                byte[] tail = new byte[keep];
                file.Position = file.Length - keep;
                int offset = 0;
                while (offset < keep) {
                    int read = file.Read(tail, offset, keep - offset);
                    if (read == 0) throw new EndOfStreamException();
                    offset += read;
                }
                file.Position = 0;
                file.Write(tail, 0, keep);
                file.SetLength(keep);
            }
            file.Position = file.Length;
            file.Write(data, count > LogLimitBytes / 2 ? count - LogLimitBytes / 2 : 0,
                Math.Min(count, LogLimitBytes / 2));
        }
    }

    private static async Task Pump(Stream source, string path) {
        byte[] buffer = new byte[8192];
        bool logging = true;
        int count;
        while ((count = await source.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) != 0) {
            if (!logging) continue;
            try { AppendTail(path, buffer, count); }
            catch (Exception error) {
                if (!(error is IOException) && !(error is UnauthorizedAccessException)) throw;
                logging = false;
                try { Console.Error.WriteLine("worker-diagnostic-write-failed:" + error.GetType().Name); }
                catch (IOException) { }
            }
        }
    }

    public static int Run(string executable, string arguments, string directory, string stdout, string stderr) {
        var start = new ProcessStartInfo(executable, arguments) {
            WorkingDirectory = directory, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (Process worker = Process.Start(start)) {
            WorkerProcessId = worker.Id;
            Task output = Pump(worker.StandardOutput.BaseStream, stdout);
            Task error = Pump(worker.StandardError.BaseStream, stderr);
            // The supervisor's job owns retirement even when a log writer fails.
            Task streams = Task.WhenAll(output, error);
            while (!worker.WaitForExit(100)) {
                if (output.IsFaulted) { worker.Kill(); output.GetAwaiter().GetResult(); }
                if (error.IsFaulted) { worker.Kill(); error.GetAwaiter().GetResult(); }
            }
            RetireDescendants();
            if (!streams.Wait(5000)) throw new IOException("Owned output pipe retirement failed");
            streams.GetAwaiter().GetResult();
            return worker.ExitCode;
        }
    }

    private static IntPtr ownedJob;
    private static void RetireDescendants() {
        if (ownedJob == IntPtr.Zero) throw new InvalidOperationException("Worker job is not attached");
        var deadline = Stopwatch.StartNew();
        int capacity = 64;
        while (deadline.ElapsedMilliseconds < 5000) {
            int size = checked(8 + IntPtr.Size * capacity);
            IntPtr info = Marshal.AllocHGlobal(size);
            try {
                if (!QueryInformationJobObject(ownedJob, 3, info, (uint)size, IntPtr.Zero)) {
                    int code = Marshal.GetLastWin32Error();
                    if (code == 234) { capacity = checked(capacity * 2); continue; }
                    throw new Win32Exception(code);
                }
                int count = Marshal.ReadInt32(info, 4);
                bool remaining = false;
                int supervisor = Process.GetCurrentProcess().Id;
                for (int index = 0; index < count; index++) {
                    int id = Marshal.ReadIntPtr(info, 8 + index * IntPtr.Size).ToInt32();
                    if (id == supervisor) continue;
                    IntPtr process = OpenProcess(0x101001, false, id);
                    if (process == IntPtr.Zero) {
                        int code = Marshal.GetLastWin32Error();
                        if (code == 87) continue;
                        throw new Win32Exception(code);
                    }
                    try {
                        bool belongs;
                        if (!IsProcessInJob(process, ownedJob, out belongs)) throw new Win32Exception(Marshal.GetLastWin32Error());
                        if (!belongs) continue;
                        remaining = true;
                        if (WaitForSingleObject(process, 0) != 0 && !TerminateProcess(process, 0))
                            throw new Win32Exception(Marshal.GetLastWin32Error());
                    } finally { CloseHandle(process); }
                }
                if (!remaining) return;
            } finally { Marshal.FreeHGlobal(info); }
            System.Threading.Thread.Sleep(10);
        }
        throw new IOException("Owned descendant retirement failed");
    }
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool QueryInformationJobObject(IntPtr job, int kind, IntPtr data, uint size, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern uint WaitForSingleObject(IntPtr process, uint milliseconds);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool SetInformationJobObject(IntPtr job, int kind, IntPtr data, uint size);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool inJob);
    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
    public static void Attach() {
        bool inJob;
        if (!IsProcessInJob(GetCurrentProcess(), IntPtr.Zero, out inJob))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        if (inJob) throw new InvalidOperationException("shared-parent-job");
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        int size = IntPtr.Size == 8 ? 144 : 112;
        IntPtr info = Marshal.AllocHGlobal(size);
        try {
            Marshal.Copy(new byte[size], 0, info, size);
            Marshal.WriteInt32(info, 16, 0x2000);
            if (!SetInformationJobObject(job, 9, info, (uint)size) ||
                !AssignProcessToJobObject(job, GetCurrentProcess())) {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(job);
                throw new Win32Exception(error);
            }
            // Only this process holds the non-inheritable handle; exit closes it.
            ownedJob = job;
        } finally { Marshal.FreeHGlobal(info); }
    }
}
