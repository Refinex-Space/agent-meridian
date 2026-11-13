import { useEffect, useRef } from 'react';

import { toHex, toRgb } from '@/lib/color';
import { cn } from '@/lib/cn';
import { formatInteger, formatMoney, formatTokens } from '@/lib/format';

import type { DailyUsage } from '../fixtures/usage';

export interface UsageChartProps {
  data: readonly DailyUsage[];
  /** 今天的数据还不完整：最后一根柱子减淡，提示里写"截至此刻"。 */
  todayPartial?: boolean;
  className?: string;
}

const WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function dayLabel(day: string, long: boolean): string {
  const [, m, d] = day.split('-').map(Number);
  if (!long) return `${m}/${d}`;
  const weekday = WEEKDAY[new Date(`${day}T00:00:00`).getDay()] ?? '';
  return `${m} 月 ${d} 日 ${weekday}`;
}

/** 读当前主题下的令牌颜色：ECharts 不认 OKLCH，换成十六进制。 */
function palette(el: Element) {
  const style = getComputedStyle(el);
  const read = (name: string) =>
    toHex(toRgb(style.getPropertyValue(`--palette-${name}`).trim()) ?? [0, 0, 0]);
  return {
    bar: read('accent'),
    barHover: read('accent-hover'),
    rule: read('rule'),
    ink: read('ink'),
    ink2: read('ink-2'),
    raised: read('surface-raised'),
  };
}

/**
 * 按天的 token 用量（单一序列的柱状图，ECharts 按需引入、懒加载）。颜色取令牌，切换主题时重绘；柱子不超过 24px、
 * 顶端 4px 圆角，网格线是 1px 刻线；悬停给出当天精确的 token 与成本；不做入场动画。读屏读到摘要与一张隐藏的表。
 */
export function UsageChart({ data, todayPartial = true, className }: UsageChartProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    let disposed = false;
    let cleanup = () => undefined as void;

    void Promise.all([
      import('echarts/core'),
      import('echarts/charts'),
      import('echarts/components'),
      import('echarts/renderers'),
    ]).then(([echarts, charts, components, renderers]) => {
      if (disposed) return;
      echarts.use([
        charts.BarChart,
        components.GridComponent,
        components.TooltipComponent,
        renderers.SVGRenderer,
      ]);
      const chart = echarts.init(el, null, { renderer: 'svg' });

      const render = () => {
        const c = palette(el);
        const last = data.length - 1;
        chart.setOption(
          {
            animation: false,
            textStyle: { fontFamily: getComputedStyle(el).fontFamily, color: c.ink2, fontSize: 12 },
            grid: { left: 0, right: 0, top: 8, bottom: 0, containLabel: true },
            xAxis: {
              type: 'category',
              data: data.map((d) => dayLabel(d.day, false)),
              axisLine: { lineStyle: { color: c.rule } },
              axisTick: { show: false },
              axisLabel: { color: c.ink2, fontSize: 12, margin: 12 },
            },
            yAxis: {
              type: 'value',
              splitNumber: 4,
              axisLabel: { color: c.ink2, fontSize: 12, formatter: (v: number) => formatTokens(v) },
              splitLine: { lineStyle: { color: c.rule, width: 1, type: 'solid' } },
            },
            tooltip: {
              trigger: 'axis',
              axisPointer: { type: 'shadow', shadowStyle: { color: c.rule, opacity: 0.4 } },
              backgroundColor: c.raised,
              borderColor: c.rule,
              borderWidth: 1,
              padding: [8, 12],
              textStyle: { color: c.ink, fontSize: 12 },
              // 提示框渲染在图表容器里，能直接用浮层阴影的令牌。
              extraCssText:
                'border-radius:8px;box-shadow:0 1px 2px var(--palette-shadow-near),0 8px 24px -6px var(--palette-shadow-far);',
              formatter: (params: unknown) => {
                const index = (
                  Array.isArray(params) ? (params[0] as { dataIndex: number }) : { dataIndex: 0 }
                ).dataIndex;
                const d = data[index];
                if (!d) return '';
                const partial = todayPartial && index === last ? '，截至此刻' : '';
                return (
                  `<div style="color:${c.ink2}">${dayLabel(d.day, true)}${partial}</div>` +
                  `<div style="font-size:14px;font-weight:600;margin-top:4px">${formatInteger(d.tokens)} token</div>` +
                  `<div style="color:${c.ink2};margin-top:2px">成本 ${formatMoney(d.cost, 'CNY')}</div>`
                );
              },
            },
            series: [
              {
                type: 'bar',
                barMaxWidth: 24,
                data: data.map((d, i) => ({
                  value: d.tokens,
                  itemStyle: {
                    color: c.bar,
                    opacity: todayPartial && i === last ? 0.45 : 1,
                    borderRadius: [4, 4, 0, 0],
                  },
                })),
                emphasis: { itemStyle: { color: c.barHover } },
              },
            ],
          },
          true,
        );
      };
      render();

      const resize = new ResizeObserver(() => chart.resize());
      resize.observe(el);
      // 主题写在最近的 data-theme 上（应用是 <html>）：变化时按新令牌重绘。
      const theme = new MutationObserver(render);
      theme.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      cleanup = () => {
        resize.disconnect();
        theme.disconnect();
        chart.dispose();
      };
    });

    return () => {
      disposed = true;
      cleanup();
    };
  }, [data, todayPartial]);

  const total = data.reduce((sum, d) => sum + d.tokens, 0);
  const cost = data.reduce((sum, d) => sum + d.cost, 0);
  return (
    <div className={cn('relative', className)}>
      <div
        ref={ref}
        role="img"
        aria-label={`最近 ${data.length} 天每天的 token 用量，合计 ${formatTokens(total)} token，成本 ${formatMoney(cost, 'CNY')}`}
        className="h-full w-full"
      />
      <table className="sr-only">
        <caption>每天的用量</caption>
        <thead>
          <tr>
            <th>日期</th>
            <th>token</th>
            <th>成本</th>
          </tr>
        </thead>
        <tbody>
          {data.map((d) => (
            <tr key={d.day}>
              <td>{dayLabel(d.day, true)}</td>
              <td>{formatInteger(d.tokens)}</td>
              <td>{formatMoney(d.cost, 'CNY')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
