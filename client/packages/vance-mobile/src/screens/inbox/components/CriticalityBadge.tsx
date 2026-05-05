import { Criticality } from '@vance/generated';
import { VBadge, type VBadgeVariant } from '@/components';

const variantByCriticality: Record<Criticality, VBadgeVariant> = {
  [Criticality.LOW]: 'default',
  [Criticality.NORMAL]: 'primary',
  [Criticality.CRITICAL]: 'danger',
};

const labelByCriticality: Record<Criticality, string> = {
  [Criticality.LOW]: 'low',
  [Criticality.NORMAL]: 'normal',
  [Criticality.CRITICAL]: 'critical',
};

interface Props {
  criticality: Criticality;
}

export function CriticalityBadge({ criticality }: Props) {
  return <VBadge variant={variantByCriticality[criticality]}>{labelByCriticality[criticality]}</VBadge>;
}
