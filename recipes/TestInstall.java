package com.vela.simulator;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** JVM-side validation of the APK installer logic against the real Termux index. */
public class TestInstall {
    public static void main(String[] args) throws Exception {
        byte[] idx = Files.readAllBytes(Paths.get(args[0]));
        Map<String, RuntimeInstaller.Pkg> repo = RuntimeInstaller.parseIndex(idx);
        System.out.println("index parsed: " + repo.size() + " packages (raw " + idx.length + "B)");
        if (repo.size() < 1000) throw new AssertionError("index parse too small!");

        List<RuntimeInstaller.Pkg> order = RuntimeInstaller.resolve(repo, Constants.RUNTIME_ROOTS);
        long total = 0;
        for (RuntimeInstaller.Pkg p : order) total += p.size;
        System.out.println("dependency closure: " + order.size() + " packages, " + RuntimeInstaller.human(total));
        for (RuntimeInstaller.Pkg p : order) {
            if (p.filename == null || p.filename.isEmpty()) throw new AssertionError("pkg without filename: " + p.name);
            System.out.println("  " + p.name + "  " + RuntimeInstaller.human(p.size));
        }
        boolean hasQemu = false;
        for (RuntimeInstaller.Pkg p : order) if (p.name.equals("qemu-system-arm-headless")) hasQemu = true;
        if (!hasQemu) throw new AssertionError("closure missing qemu-system-arm-headless!");
        System.out.println("TEST OK");
    }
}
