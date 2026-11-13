import { CodeBlock } from '@/components/domain/code-block';
import { KeyValueList } from '@/components/domain/key-value-list';

const DECLARE = `{
  "agent": "agt_…",
  "environment": "env_…",
  "end_user_id": "u_8231",
  "custom_tools": [
    {
      "name": "create_refund",
      "description": "为订单发起退款，返回退款单号",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id": { "type": "string" },
          "amount": { "type": "number" }
        },
        "required": ["order_id", "amount"]
      },
      "side_effect": "non_idempotent"
    }
  ]
}`;

const RESPOND = `{
  "events": [
    {
      "type": "user.custom_tool_result",
      "action_id": "act_…",
      "content": [{ "type": "text", "text": "已发起退款，退款单号 RF20261113001" }],
      "error": false
    }
  ]
}`;

const RULES = [
  {
    label: '名称',
    value: '小写字母开头，只用小写字母、数字与下划线，至多 64 个字符；同一会话内不重复，不能与平台工具重名',
  },
  { label: '说明', value: '给模型看的用途说明，1 到 2048 个字符' },
  { label: '入参', value: 'JSON Schema 对象，模型按它生成调用参数' },
  {
    label: '副作用等级',
    value: '可选；有副作用的工具在崩溃恢复时不重放，状态未知时请人确认',
  },
  { label: '数量', value: '每个会话至多 64 个' },
];

function Step({
  title,
  request,
  code,
  label,
}: {
  title: string;
  request: string;
  code: string;
  label: string;
}) {
  return (
    <li className="flex flex-col gap-3">
      <h3 className="text-14 font-semibold">{title}</h3>
      <p className="font-mono text-12 text-ink-2">{request}</p>
      <CodeBlock code={code} language="json" label={label} collapseAfter={0} />
    </li>
  );
}

/**
 * 自定义工具的说明（console-api-map.md「6 能力」）：自定义工具由业务系统在创建会话时声明、在业务系统里执行，平台不做项目级
 * 登记；这里写清声明与回传的请求，以及命名规则。某个会话声明了哪些工具在会话详情里看。
 */
export function CustomToolsGuide() {
  return (
    <div className="flex max-w-220 flex-col gap-8 overflow-y-auto pt-4 pb-12">
      <p className="max-w-160 text-14">
        {
          '自定义工具由业务系统在创建会话时声明、由业务系统执行。智能体调用它时本轮暂停，会话里出现一个等待回传结果的待处理动作；业务系统执行后回传结果，智能体接着往下做。平台不登记项目级的自定义工具。'
        }
      </p>
      <ol className="flex flex-col gap-8">
        <Step
          title="创建会话时声明工具"
          request="POST /v1/sessions"
          code={DECLARE}
          label="创建会话并声明自定义工具的请求体"
        />
        <li className="flex flex-col gap-2">
          <h3 className="text-14 font-semibold">等待智能体调用</h3>
          <p className="max-w-160 text-14">
            事件流中出现 <code className="font-mono text-12">agent.custom_tool_use</code>
            ，带工具名与入参；同时会话进入等待处理，待处理动作的类型是{' '}
            <code className="font-mono text-12">custom_tool_result</code>。
          </p>
        </li>
        <Step
          title="执行后回传结果"
          request="POST /v1/sessions/{session_id}/events"
          code={RESPOND}
          label="回传自定义工具结果的请求体"
        />
      </ol>
      <section aria-labelledby="custom-tool-rules" className="flex flex-col gap-3">
        <h3 id="custom-tool-rules" className="text-14 font-semibold">
          声明的规则
        </h3>
        <KeyValueList items={RULES} />
      </section>
    </div>
  );
}
