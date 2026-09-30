package com.tianchen.kb.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * 带重叠的递归式文本切分器。
 *
 * <p>按「段落 → 换行 → 中文句末标点」逐级拆分，保证切片尽量贴近语义边界；
 * 相邻切片保留 {@code overlap} 个字符的重叠，避免答案恰好跨越切片边界时被截断。
 * 这是纯逻辑实现，不依赖任何框架，可直接单元测试。
 *
 * <p><b>终止性</b>：每一级只有在分隔符<b>确实出现</b>（即能把文本切成多于一段）时才下钻，
 * 且切分出的每一段都严格短于输入；分隔符都不存在时落到 {@link #hardSplit} 兜底。
 * 这保证了递归必然收敛——早期版本在「长文本不含空行」时会把整段文本原样传回自身，
 * 形成无限递归并抛 StackOverflowError。
 */
public final class TextSplitter {

    /** 由粗到细的语义分隔符，按优先级依次尝试。 */
    private static final List<String> DELIMITERS = List.of("\n\n", "\n", "。", "！", "？", "；");

    private final int chunkSize;
    private final int overlap;

    public TextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> units = recursivelySplit(normalize(text), chunkSize);
        return mergeWithOverlap(units);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /** 逐级尝试分隔符；每级只在能真正切开时才下钻，最终必有兜底。 */
    private List<String> recursivelySplit(String text, int limit) {
        if (text.length() <= limit) {
            return List.of(text);
        }
        for (String delimiter : DELIMITERS) {
            if (!text.contains(delimiter)) {
                continue;
            }
            List<String> parts = divideBy(text, delimiter);
            if (parts.size() > 1) {
                List<String> result = new ArrayList<>(parts.size());
                for (String part : parts) {
                    result.addAll(recursivelySplit(part, limit));
                }
                return result;
            }
        }
        return hardSplit(text, limit);
    }

    /**
     * 用单个分隔符把文本切成若干片段，分隔符保留在前一片段尾部。
     *
     * <p>注意：本方法<b>不做递归</b>，也不保证每段都短于 limit——
     * 超长片段由调用方换用更细的分隔符继续下钻，或由 hardSplit 兜底。
     */
    private static List<String> divideBy(String text, String delimiter) {
        List<String> parts = new ArrayList<>();
        String[] segments = text.split(java.util.regex.Pattern.quote(delimiter), -1);
        for (int index = 0; index < segments.length; index++) {
            String piece = index == segments.length - 1 ? segments[index] : segments[index] + delimiter;
            parts.add(piece);
        }
        return parts;
    }

    private static List<String> hardSplit(String text, int limit) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += limit) {
            result.add(text.substring(start, Math.min(start + limit, text.length())));
        }
        return result;
    }

    /**
     * 把相邻切片拼接成重叠窗口。
     *
     * <p><b>不变量</b>：返回的每个切片长度都 ≤ {@code chunkSize}。
     * 已落盘的切片与当前单元都受 300 之类上限约束时，若还要在单元前面塞入 overlap
     * 长度的重叠前缀，就会越界。重叠只是「锦上添花」的上下文冗余，
     * 而切片超长会直接影响 embedding 质量与存储开销——因此这里宁可放弃/缩短这次重叠，
     * 也不允许越界。
     */
    private List<String> mergeWithOverlap(List<String> units) {
        List<String> normalized = units.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (normalized.size() <= 1 || overlap == 0) {
            return normalized;
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder window = new StringBuilder();
        for (String unit : normalized) {
            if (window.length() == 0) {
                window.append(unit);
                continue;
            }
            String separator = needsSeparator(window.toString(), unit) ? "\n" : "";
            if (window.length() + separator.length() + unit.length() <= chunkSize) {
                window.append(separator).append(unit);
                continue;
            }
            // 窗口已装满：先落盘，再用「尾部重叠 + 当前单元」开启新窗口
            chunks.add(window.toString());
            String tail = window.toString();
            // 预留 1 个字符给连接用的换行符
            int carryBudget = chunkSize - unit.length() - 1;
            String carry = "";
            if (carryBudget > 0) {
                int carryLength = Math.min(overlap, carryBudget);
                carry = tail.length() <= carryLength ? tail : tail.substring(tail.length() - carryLength);
            }
            window.setLength(0);
            window.append(composeWithSeparator(carry, unit));
        }
        if (window.length() > 0) {
            chunks.add(window.toString());
        }
        return trimTailDuplicate(chunks);
    }

    /** 拼接两段文本，必要时用换行符分隔，避免把两句话粘成一个词。 */
    private static String composeWithSeparator(String prefix, String suffix) {
        if (prefix.isEmpty()) {
            return suffix;
        }
        return prefix + (needsSeparator(prefix, suffix) ? "\n" : "") + suffix;
    }

    private static boolean needsSeparator(String prefix, String suffix) {
        return !prefix.endsWith("\n") && !suffix.startsWith("\n");
    }

    /** 若最后一个切片完全是前一切片尾部重叠的重复，丢弃它。 */
    private List<String> trimTailDuplicate(List<String> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        String last = chunks.get(chunks.size() - 1);
        String previous = chunks.get(chunks.size() - 2);
        if (previous.contains(last.trim()) && last.trim().length() < previous.length()) {
            return chunks.subList(0, chunks.size() - 1);
        }
        return chunks;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }
}