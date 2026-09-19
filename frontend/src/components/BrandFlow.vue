<script setup lang="ts">
import { useId } from "vue";
const glowId = useId();
function curve(n: number) {
  return `M -180 ${70 + n * 4} C 290 ${-60 + n * 7}, 570 ${680 - n * 4}, 1380 ${150 + n * 3}`;
}
</script>
<template>
  <div class="brand-flow" aria-hidden="true">
    <svg
      viewBox="0 0 1200 600"
      fill="none"
      preserveAspectRatio="xMidYMid slice"
    >
      <defs>
        <filter :id="glowId" x="-30%" y="-60%" width="160%" height="220%">
          <feGaussianBlur stdDeviation="12" />
        </filter>
      </defs>
      <g :filter="`url(#${glowId})`" opacity=".65">
        <path
          v-for="n in 8"
          :key="n"
          :d="curve(n * 5)"
          stroke="#486aff"
          stroke-width="14"
        />
      </g>
      <path
        v-for="n in 56"
        :key="n"
        :d="curve(n)"
        :stroke="n % 4 === 0 ? '#cef3ff' : n % 3 === 0 ? '#78b7ff' : '#5967ff'"
        :stroke-width="n % 7 === 0 ? 2.8 : 0.8"
        :opacity="0.12 + (n % 7) * 0.105"
      />
      <circle
        v-for="n in 24"
        :key="`star-${n}`"
        :cx="(n * 173) % 1200"
        :cy="(n * 83) % 510"
        :r="n % 4 === 0 ? 1.8 : 0.7"
        fill="#bcd9ff"
        :opacity="0.15 + (n % 5) * 0.1"
      />
    </svg>
  </div>
</template>
