export const DECISION_TOOL = 'submit_timetable_decision';

// Non-strict tool schema for OpenAI-compatible APIs. The local executor remains
// authoritative for each action's fields, scope, dates, IDs and atomic commit.
export const decisionTool = {
  type: 'function',
  function: {
    name: DECISION_TOOL,
    description: '提交课表操作决定或追问。将系统提示中的完整 reply/action 对象作为参数；信息不足时 action 为 null。此函数只提交建议，应用校验后才执行。',
    parameters: {
      type: 'object', required: ['reply', 'action'], additionalProperties: false,
      properties: {
        reply: { type: 'string', description: '中文说明或需要用户补充的问题' },
        action: { anyOf: [
          { type: 'null' },
          { type: 'object', required: ['type'], properties: {
            type: { type: 'string', enum: ['add', 'delete', 'metadata', 'change', 'batch'] },
          }, additionalProperties: true, description: '严格使用系统提示规定的操作格式；可选字段未提供时省略' },
        ] },
      },
    },
  },
};
