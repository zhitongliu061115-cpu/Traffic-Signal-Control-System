<script setup lang="ts">
// ================================================================
// MapLegend — 地图右下角图例
// ================================================================
defineProps<{ visible: boolean }>()
</script>

<template>
  <div v-if="visible" class="map-legend">
    <span class="ml-item"><span class="ml-swatch ml-swatch--green" /> 畅通</span>
    <span class="ml-item"><span class="ml-swatch ml-swatch--yellow" /> 缓行</span>
    <span class="ml-item"><span class="ml-swatch ml-swatch--orange" /> 拥堵</span>
    <span class="ml-item"><span class="ml-swatch ml-swatch--red" /> 严重拥堵</span>
    <span class="ml-item"><span class="ml-swatch ml-swatch--cyan" /> 应急绿波</span>
  </div>
</template>

<style scoped>
.map-legend {
  position: absolute; right: 12px; bottom: 10px; z-index: 5;
  display: flex; flex-wrap: wrap; gap: 6px 12px;
  padding: 6px 12px;
  background: rgba(4,21,39,0.75); border: 1px solid rgba(0,212,255,0.24);
  backdrop-filter: blur(4px);
}
.ml-item { font-size: 10px; color: #8da8c5; display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.ml-swatch { width: 12px; height: 3px; border-radius: 1px; box-shadow: 0 0 5px currentColor; }
.ml-swatch--green  { background: #22D3A0; }
.ml-swatch--yellow { background: #FFB800; }
.ml-swatch--orange { background: #FF7A45; }
.ml-swatch--red    { background: #FF4D6D; }
.ml-swatch--cyan   { background: #00E5FF; }
</style>
