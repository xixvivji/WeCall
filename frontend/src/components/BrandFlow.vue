<template>
  <div class="brand-flow" aria-hidden="true">
    <svg
      viewBox="0 0 1200 600"
      fill="none"
      preserveAspectRatio="xMidYMid slice"
    >
      <path
        v-for="n in 18"
        :key="n"
        :d="`M ${-220 + n * 5} ${180 + n * 12} C 260 ${-100 + n * 18}, 650 ${740 - n * 15}, 1440 ${40 + n * 15}`"
        :stroke="n % 3 === 0 ? '#91d7ff' : '#637aff'"
        :stroke-width="n % 5 === 0 ? 2.5 : 1"
        :opacity="0.15 + (n % 5) * 0.12"
      />
    </svg>
  </div>
</template>
