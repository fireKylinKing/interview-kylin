package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 简历分析响应DTO
 */
public record ResumeAnalysisResponse(
    // 总分 (0-100)
    int overallScore,
    
    // 各维度评分
    ScoreDetail scoreDetail,
    
    // 简历摘要
    String summary,
    
    // 优点列表
    List<String> strengths,
    
    // 改进建议列表
    List<Suggestion> suggestions,
    
    // 原始简历文本
    String originalText
) {
    
    /**
     * 各维度评分详情（前五项之和 = overallScore，满分 100；jdAlignmentScore 不计入总分）
     */
    public record ScoreDetail(
        int projectScore,       // 项目经验与技术深度 (0-40)
        int skillMatchScore,    // 技能匹配度 (0-20)
        int contentScore,       // 内容完整性 (0-15)
        int structureScore,     // 结构清晰度 (0-15)
        int expressionScore,    // 表达专业性 (0-10)
        int jdAlignmentScore    // JD 对齐度 (0-20)，不计入总分，无 JD 时为 0
    ) {}
    
    /**
     * 改进建议
     */
    public record Suggestion(
        String category,        // 建议类别：内容、格式、技能、项目等
        String priority,        // 优先级：高、中、低
        String issue,           // 问题描述
        String recommendation   // 具体建议
    ) {}
}
