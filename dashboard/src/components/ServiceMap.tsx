import { useEffect, useRef } from 'react';
import * as d3 from 'd3';
import type { Topology, TopologyNode } from '../types';

interface Props {
  topology:         Topology;
  interceptedIds:   Set<string>;
  onNodeClick:      (nodeName: string) => void;
  collapsed:        boolean;
  onToggleCollapse: () => void;
}

interface SimNode extends d3.SimulationNodeDatum {
  id:        string;
  name:      string;
  namespace: string;
  status?:   string;
  radius:    number;
}

interface SimLink extends d3.SimulationLinkDatum<SimNode> {
  requestCount: number;
  avgLatency:   number;
}

export function ServiceMap({ topology, interceptedIds, onNodeClick, collapsed, onToggleCollapse }: Props) {
  const svgRef = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (collapsed || !svgRef.current || topology.nodes.length === 0) return;

    const svg    = d3.select(svgRef.current);
    const width  = svgRef.current.clientWidth  || 600;
    const height = svgRef.current.clientHeight || 240;

    svg.selectAll('*').remove();

    // ── Scale node radius by traffic volume ──────────────────────────────────
    const trafficByNode = new Map<string, number>();
    topology.edges.forEach(e => {
      const src = typeof e.source === 'string' ? e.source : (e.source as SimNode).id;
      trafficByNode.set(src, (trafficByNode.get(src) ?? 0) + e.requestCount);
    });
    const maxTraffic = Math.max(...Array.from(trafficByNode.values()), 1);
    const rScale = d3.scaleSqrt().domain([0, maxTraffic]).range([14, 32]);

    const nodes: SimNode[] = topology.nodes.map(n => ({
      id:        n.id,
      name:      n.name,
      namespace: n.namespace,
      status:    n.status,
      radius:    rScale(trafficByNode.get(n.name) ?? 0),
    }));

    const nodeById = new Map(nodes.map(n => [n.id, n]));

    const links: SimLink[] = topology.edges
      .map(e => ({
        source:       nodeById.get(typeof e.source === 'string' ? e.source : e.source) ?? nodes[0],
        target:       nodeById.get(typeof e.target === 'string' ? e.target : e.target) ?? nodes[0],
        requestCount: e.requestCount,
        avgLatency:   e.avgLatency,
      }));

    // ── D3 force simulation ──────────────────────────────────────────────────
    const sim = d3.forceSimulation<SimNode>(nodes)
      .force('link', d3.forceLink<SimNode, SimLink>(links).id(d => d.id).distance(120))
      .force('charge', d3.forceManyBody().strength(-400))
      .force('center', d3.forceCenter(width / 2, height / 2))
      .force('collision', d3.forceCollide<SimNode>(d => d.radius + 12));

    const g = svg.append('g');

    // Zoom
    svg.call(d3.zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.3, 3])
      .on('zoom', e => g.attr('transform', e.transform)));

    // ── Arrow marker ─────────────────────────────────────────────────────────
    svg.append('defs').append('marker')
      .attr('id', 'arrow')
      .attr('viewBox', '0 -4 8 8')
      .attr('refX', 8).attr('refY', 0)
      .attr('markerWidth', 4).attr('markerHeight', 4)
      .attr('orient', 'auto')
      .append('path')
      .attr('d', 'M0,-4L8,0L0,4')
      .attr('fill', '#30363d');

    // ── Links ────────────────────────────────────────────────────────────────
    const linkThicknessScale = d3.scaleLog().domain([1, maxTraffic]).range([1, 4]).clamp(true);

    const linkEl = g.append('g').selectAll('line')
      .data(links).join('line')
      .attr('stroke', '#30363d')
      .attr('stroke-width', d => linkThicknessScale(d.requestCount))
      .attr('marker-end', 'url(#arrow)')
      .attr('opacity', 0.6);

    // ── Nodes ────────────────────────────────────────────────────────────────
    const nodeGroup = g.append('g').selectAll<SVGGElement, SimNode>('g')
      .data(nodes).join('g')
      .attr('cursor', 'pointer')
      .on('click', (_, d) => onNodeClick(d.name))
      .call(d3.drag<SVGGElement, SimNode>()
        .on('start', (event, d) => { if (!event.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
        .on('drag',  (event, d) => { d.fx = event.x; d.fy = event.y; })
        .on('end',   (event, d) => { if (!event.active) sim.alphaTarget(0); d.fx = null; d.fy = null; }));

    // Pulsing ring for intercepted nodes
    nodeGroup.filter(d => interceptedIds.has(d.name))
      .append('circle')
      .attr('r', d => d.radius + 6)
      .attr('fill', 'none')
      .attr('stroke', '#3fb950')
      .attr('stroke-width', 1.5)
      .attr('opacity', 0)
      .each(function() {
        const el = d3.select(this);
        function pulse() {
          el.attr('opacity', 0.8).attr('r', function(d: SimNode) { return d.radius + 6; })
            .transition().duration(1200).attr('opacity', 0)
            .attr('r', function(d: SimNode) { return d.radius + 16; })
            .on('end', pulse);
        }
        pulse();
      });

    // Main circle
    nodeGroup.append('circle')
      .attr('r', d => d.radius)
      .attr('fill', d => {
        if (interceptedIds.has(d.name)) return 'rgba(63,185,80,0.15)';
        return '#1c2230';
      })
      .attr('stroke', d => {
        if (interceptedIds.has(d.name)) return '#3fb950';
        return '#30363d';
      })
      .attr('stroke-width', 1.5);

    // Label
    nodeGroup.append('text')
      .text(d => d.name.replace('-service', ''))
      .attr('text-anchor', 'middle')
      .attr('dy', '0.35em')
      .attr('font-family', 'JetBrains Mono, monospace')
      .attr('font-size', 10)
      .attr('fill', '#8b949e')
      .attr('pointer-events', 'none');

    // ── Simulation tick ──────────────────────────────────────────────────────
    sim.on('tick', () => {
      linkEl
        .attr('x1', d => (d.source as SimNode).x!)
        .attr('y1', d => (d.source as SimNode).y!)
        .attr('x2', d => {
          const s = d.source as SimNode, t = d.target as SimNode;
          const dx = t.x! - s.x!, dy = t.y! - s.y!;
          const dist = Math.sqrt(dx*dx + dy*dy) || 1;
          return t.x! - (dx / dist) * (t.radius + 8);
        })
        .attr('y2', d => {
          const s = d.source as SimNode, t = d.target as SimNode;
          const dx = t.x! - s.x!, dy = t.y! - s.y!;
          const dist = Math.sqrt(dx*dx + dy*dy) || 1;
          return t.y! - (dy / dist) * (t.radius + 8);
        });
      nodeGroup.attr('transform', d => `translate(${d.x},${d.y})`);
    });

    return () => { sim.stop(); };
  }, [topology, interceptedIds, collapsed]);

  return (
    <div className={`service-map-container ${collapsed ? 'collapsed' : 'expanded'}`}>
      <div className="map-toggle-bar" onClick={onToggleCollapse} id="service-map-toggle">
        <span>{collapsed ? '▶' : '▼'}</span>
        Service Map
        <span style={{ marginLeft: 'auto', fontWeight: 400 }}>
          {topology.nodes.length} services · {topology.edges.length} connections
        </span>
      </div>
      {!collapsed && (
        <svg ref={svgRef} className="service-map-svg" />
      )}
    </div>
  );
}
