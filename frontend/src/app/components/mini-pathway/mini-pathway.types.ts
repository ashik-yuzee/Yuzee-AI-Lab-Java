import { YuzeeResponseV13 } from '../../models/types';

/**
 * Shape of one saved mini-pathway run, as returned by:
 *   GET  /api/conversations/:id/mini-pathway            -> MiniPathwayRun[]
 *   POST /api/conversations/:id/mini-pathway (SSE done)  -> { done: true, pathway: MiniPathwayRun }
 * See ChatController.generateMiniPathway/getMiniPathways (backend/.../ChatController.java).
 */
export interface MiniPathwayRun {
  id: string;
  goal: string;
  report: YuzeeResponseV13;
  createdAt: number;
}
