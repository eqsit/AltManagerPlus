package com.dummymod.ai;

import com.google.gson.*;
import java.util.*;

/** A bounded, declarative design authored by the model. Never executes model code. */
public final class BuildPlan {
    public static final int MAX_SIZE = 96, MAX_CELLS = 50000;
    public record Cell(int x, int y, int z) { }
    public final String name;
    public final int[] origin;
    public final Map<Cell, String> cells;
    public final int width, height, length;
    public final JsonObject source;
    private BuildPlan(JsonObject json, String name, int[] origin, Map<Cell, String> cells) {
        this.source = json.deepCopy(); this.name = name; this.origin = origin; this.cells = Collections.unmodifiableMap(cells);
        width = cells.keySet().stream().mapToInt(Cell::x).max().orElseThrow() + 1;
        height = cells.keySet().stream().mapToInt(Cell::y).max().orElseThrow() + 1;
        length = cells.keySet().stream().mapToInt(Cell::z).max().orElseThrow() + 1;
    }
    public static BuildPlan parse(JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : "Постройка";
        if (name.length() > 100) name = name.substring(0, 100);
        int[] origin = triple(json.getAsJsonArray("origin"));
        if (Math.abs((long) origin[0]) > 29999000 || Math.abs((long) origin[2]) > 29999000 || Math.abs((long) origin[1]) > 4096) throw new IllegalArgumentException("Неверная точка строительства");
        JsonArray operations = json.getAsJsonArray("operations");
        if (operations == null || operations.isEmpty() || operations.size() > 1024) throw new IllegalArgumentException("Нужно 1–1024 операций");
        Map<Cell, String> cells = new HashMap<>();
        long work = 0;
        for (JsonElement el : operations) {
            JsonObject op = el.getAsJsonObject();
            String type = op.has("shape") ? op.get("shape").getAsString() : "box";
            if (!Set.of("box", "hollow_box", "sphere", "cylinder").contains(type)) throw new IllegalArgumentException("Неизвестная форма: " + type);
            String block = op.get("block").getAsString();
            if (block.length() > 256 || !block.matches("[a-z0-9_:]+(?:\\[[a-z0-9_=,]+\\])?")) throw new IllegalArgumentException("Неверный блок");
            int[] a = triple(op.getAsJsonArray("from")), b = triple(op.getAsJsonArray("to"));
            for (int i = 0; i < 3; i++) if (a[i] < 0 || b[i] < a[i] || b[i] >= MAX_SIZE) throw new IllegalArgumentException("Размер проекта: до 96 блоков по каждой оси; координаты from <= to");
            work += (long)(b[0]-a[0]+1)*(b[1]-a[1]+1)*(b[2]-a[2]+1);
            if (work > 2000000) throw new IllegalArgumentException("Слишком много операций; раздели проект на части");
            for (int y = a[1]; y <= b[1]; y++) for (int x = a[0]; x <= b[0]; x++) for (int z = a[2]; z <= b[2]; z++) {
                if (type.equals("hollow_box") && x != a[0] && x != b[0] && y != a[1] && y != b[1] && z != a[2] && z != b[2]) continue;
                double dx = normalized(x, a[0], b[0]), dy = normalized(y, a[1], b[1]), dz = normalized(z, a[2], b[2]);
                if (type.equals("sphere") && dx*dx+dy*dy+dz*dz > 1.00001) continue;
                if (type.equals("cylinder") && dx*dx+dz*dz > 1.00001) continue;
                cells.put(new Cell(x,y,z), block);
                if (cells.size() > MAX_CELLS) throw new IllegalArgumentException("До 50000 блоков на проект; раздели большую постройку на части");
            }
        }
        if (cells.isEmpty()) throw new IllegalArgumentException("Пустой проект");
        Map<Cell,String> sorted = new LinkedHashMap<>();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z))).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new BuildPlan(json, name, origin, sorted);
    }
    private static double normalized(int v, int a, int b) { return (v-(a+b)/2.0)/((b-a+1)/2.0); }
    private static int[] triple(JsonArray a) {
        if (a == null || a.size() != 3) throw new IllegalArgumentException("Нужны три целые координаты");
        int[] out = new int[3];
        for (int i=0;i<3;i++) {
            double n = a.get(i).getAsDouble();
            if (!Double.isFinite(n) || n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException("Нужны целые координаты");
            out[i]=(int)n;
        }
        return out;
    }
}
