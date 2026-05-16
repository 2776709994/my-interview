请对以下语音面试的问答记录进行评估：

{qaRecords}

# Output Format
请严格按照以下 JSON 结构输出（不要输出任何其他内容）：
{
  "overallScore": 0-100,
  "overallFeedback": "总体评价",
  "strengths": ["优点1", "优点2"],
  "improvements": ["改进建议1", "改进建议2"],
  "questionEvaluations": [
    {
      "questionIndex": 0,
      "question": "问题内容",
      "category": "类别",
      "userAnswer": "候选人回答",
      "score": 0-100,
      "feedback": "针对该题的评价"
    }
  ]
}
