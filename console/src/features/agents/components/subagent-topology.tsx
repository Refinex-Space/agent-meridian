import '@xyflow/react/dist/base.css';

import { Handle, Position, ReactFlow, type Edge, type Node, type NodeProps } from '@xyflow/react';
import { memo, useMemo } from 'react';

import { AgentGlyph, ThreadGlyph } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { cn } from '@/lib/cn';

/** 拓扑图中的一个智能体（主智能体或子智能体）。 */
export interface TopologyAgent {
  name: string;
  modelName: string;
  /** 工具集：平台工具名与 MCP 工具全名。 */
  tools: readonly string[];
}

export interface SubagentTopologyProps {
  main: TopologyAgent;
  subagents: readonly TopologyAgent[];
  /** 点子智能体节点：跳到它的定义。 */
  onSelect?: (name: string) => void;
}

type AgentNodeData = { agent: TopologyAgent; role: 'main' | 'subagent' };
type AgentFlowNode = Node<AgentNodeData, 'agent'>;

const NODE_WIDTH = 288;
const COLUMN_GAP = 160;
const ROW_GAP = 24;
/** 每行大约放下的工具标签数，用来估算节点高度、排开纵向位置（节点实际高度由 xyflow 测量）。 */
const CHIPS_PER_ROW = 3;

function estimateHeight(agent: TopologyAgent): number {
  return 88 + Math.max(1, Math.ceil(agent.tools.length / CHIPS_PER_ROW)) * 26;
}

/** 节点：名字、模型、工具集；主智能体用智能体图标，子智能体用线程图标（子智能体执行在子线程上）。 */
const AgentNodeView = memo(function AgentNodeView({ data }: NodeProps<AgentFlowNode>) {
  const { agent, role } = data;
  return (
    <div
      className={cn(
        'flex flex-col gap-2 rounded-panel border bg-surface p-3 text-left',
        role === 'main' ? 'border-ink-2' : 'border-rule-strong',
      )}
      style={{ width: NODE_WIDTH }}
    >
      {role === 'subagent' && <Handle type="target" position={Position.Left} isConnectable={false} />}
      <div className="flex items-start gap-2">
        <span className="flex h-5 items-center">
          <Icon glyph={role === 'main' ? AgentGlyph : ThreadGlyph} className="text-ink-2" />
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="truncate font-mono text-13 font-medium" title={agent.name}>
            {agent.name}
          </span>
          <span className="truncate font-mono text-12 text-ink-2">{agent.modelName}</span>
        </div>
      </div>
      <ul aria-label={`${agent.name} 的工具`} className="flex flex-wrap gap-1">
        {agent.tools.length === 0 && <li className="text-12 text-ink-2">没有工具</li>}
        {agent.tools.map((tool) => (
          <li
            key={tool}
            className={cn(
              'max-w-full truncate rounded-control bg-surface-inset px-1 font-mono text-12 leading-5',
              tool === 'spawn_subagent' ? 'text-accent' : 'text-ink',
            )}
          >
            {tool}
          </li>
        ))}
      </ul>
      {role === 'main' && <Handle type="source" position={Position.Right} isConnectable={false} />}
    </div>
  );
});

const NODE_TYPES = { agent: AgentNodeView };

/** 主智能体在左、子智能体在右纵向排开；主智能体对齐子智能体的中线。 */
export function layoutTopology(main: TopologyAgent, subagents: readonly TopologyAgent[]) {
  const heights = subagents.map(estimateHeight);
  const total = heights.reduce((sum, h) => sum + h, 0) + ROW_GAP * Math.max(0, subagents.length - 1);
  const mainHeight = estimateHeight(main);
  const nodes: AgentFlowNode[] = [
    {
      id: `main:${main.name}`,
      type: 'agent',
      position: { x: 0, y: Math.max(0, (total - mainHeight) / 2) },
      data: { agent: main, role: 'main' },
      ariaLabel: `主智能体 ${main.name}`,
    },
  ];
  const edges: Edge[] = [];
  let y = 0;
  subagents.forEach((sub, i) => {
    nodes.push({
      id: `sub:${sub.name}`,
      type: 'agent',
      position: { x: NODE_WIDTH + COLUMN_GAP, y },
      data: { agent: sub, role: 'subagent' },
      ariaLabel: `子智能体 ${sub.name}`,
    });
    edges.push({
      id: `edge:${sub.name}`,
      source: `main:${main.name}`,
      target: `sub:${sub.name}`,
      type: 'smoothstep',
      style: { stroke: 'var(--palette-lane)', strokeWidth: 1 },
    });
    y += heights[i]! + ROW_GAP;
  });
  return { nodes, edges };
}

/**
 * 子智能体拓扑（xyflow）：主智能体与它可以拉起的子智能体、各自的工具集（T22-A04）。只读：节点不可拖动、不可连线，可以平移与缩放；
 * 点子智能体跳到下方它的定义。随详情页的"子智能体"标签页懒加载。
 */
export default function SubagentTopology({ main, subagents, onSelect }: SubagentTopologyProps) {
  const { nodes, edges } = useMemo(() => layoutTopology(main, subagents), [main, subagents]);
  return (
    <div className="h-full w-full [&_.react-flow__handle]:size-1.5 [&_.react-flow__handle]:border-0 [&_.react-flow__handle]:bg-lane">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        fitViewOptions={{ padding: 0.15, maxZoom: 1 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        edgesFocusable={false}
        minZoom={0.4}
        maxZoom={1.5}
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_, node) => {
          if (node.data.role === 'subagent') onSelect?.(node.data.agent.name);
        }}
        aria-label="子智能体拓扑"
      />
    </div>
  );
}
